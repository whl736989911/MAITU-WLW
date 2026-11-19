package com.fireplatform.hikvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fireplatform.identity.AuthService;
import com.fireplatform.identity.ManagementGuard;
import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.AuditService;
import com.fireplatform.shared.Digests;
import com.fireplatform.shared.IdempotencyStore;
import com.fireplatform.shared.Page;
import com.fireplatform.shared.PageSizePolicy;
import com.fireplatform.shared.Pagination;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicit, durable Hik subscription management. Inspect/preview/reconcile only read; execute marks the
 * intent IN_FLIGHT before the vendor write and confirms via a list read-back. The vendor publishes no CAS,
 * idempotency key, retry or delivery SLA, so a read/write race window exists and is disclosed. Raw target
 * URLs and credentials never leave the server; only origins and digests are returned or stored in clear.
 */
@Service
public class HikvisionSubscriptionService {
    public static final long PREVIEW_TTL_SECONDS = 900;
    private static final String ADD = "ADD", UPDATE = "UPDATE", DELETE = "DELETE";
    private static final String[] SUPPORTED = {"980002", "980004", "980008", "980016"};

    public record Limitation(boolean vendorCasSupported, boolean vendorRetryContract, boolean vendorDeliverySla, String note) { }
    public record RemoteRow(String messageType, String destinationOrigin, boolean matchesReceiver, String urlSha256,
                            OffsetDateTime updatedAt, boolean supported, boolean duplicate) { }
    public record Inspect(OffsetDateTime checkedAt, long receiverRevision, long hikvisionRevision,
                          String accountFingerprintMasked, String receiverOrigin, boolean receiverReady,
                          List<RemoteRow> items, Map<String, String> digests, Limitation limitations) { }
    public record PreviewView(String operationId, String operation, String messageType, String state,
                              boolean noWriteRequired, String currentOrigin, String intendedOrigin,
                              boolean matchesReceiver, boolean overwriteRequired, String remoteDigest,
                              String remoteState, String previousUrlSha256, long receiverRevision,
                              long hikvisionRevision, String accountFingerprintMasked, OffsetDateTime expiresAt,
                              String note, Limitation limitations) { }
    public record AttemptView(String id, int attemptNo, String phase, String outcome, String vendorCode,
                              JsonNode detail, OffsetDateTime startedAt, OffsetDateTime finishedAt) { }
    public record ObservationView(String id, String trigger, String listDigest, JsonNode snapshot,
                                  OffsetDateTime observedAt) { }
    public record OperationView(String id, String operation, String messageType, String state, long revision,
                                String intendedOrigin, String previousOrigin, boolean matchesReceiver,
                                boolean overwriteRequired, boolean overwriteConfirmed, String remoteDigest,
                                String remoteState, String vendorCode, JsonNode result, String accountFingerprintMasked,
                                String actorId, String reason, OffsetDateTime createdAt, OffsetDateTime startedAt,
                                OffsetDateTime finishedAt, OffsetDateTime expiresAt, List<AttemptView> attempts,
                                List<ObservationView> observations) { }
    public record OperationSummary(String id, String operation, String messageType, String state, long revision,
                                   String intendedOrigin, String previousOrigin, boolean matchesReceiver,
                                   boolean overwriteRequired, String vendorCode, String actorId,
                                   OffsetDateTime createdAt, OffsetDateTime finishedAt) { }

    private record Receiver(String origin, String url, String accountFingerprint, long revision, boolean enabled) { }
    private record Capture(Receiver receiver, HikvisionConfigService.Runtime runtime, String account,
                           long hikvisionRevision, HikvisionClient.Session session) { }
    private record Row(String messageType, String url, String origin, boolean matchesReceiver, String urlSha256,
                       OffsetDateTime updatedAt) { }
    private record RemoteList(OffsetDateTime observedAt, List<Row> rows,
                              Map<String, List<Row>> byType, Map<String, String> digests) { }
    private record Target(String messageType, Row current, String intendedUrl, String intendedOrigin,
                          String intendedSha, String remoteDigest, String remoteState, boolean overwriteRequired,
                          boolean noWriteRequired, String note) { }
    private record Operation(String id, String operation, String messageType, long receiverRevision,
                             long hikvisionRevision, String accountFingerprint, String intendedUrlSha,
                             String intendedOrigin, String previousUrlSha, String previousOrigin, String remoteDigest,
                             String remoteState, boolean overwriteRequired, boolean overwriteConfirmed,
                             String requestDigest, String state, long revision, String actorId, String reason,
                             String vendorCode, String resultJson, OffsetDateTime createdAt, OffsetDateTime startedAt,
                             OffsetDateTime finishedAt, OffsetDateTime updatedAt) { }
    private record Outcome(String state, String attemptOutcome, String vendorCode, JsonNode result, String note) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final HikvisionConfigService hikvision;
    private final HikvisionConfigSecrets secrets;
    private final HikvisionCallbackService callbacks;
    private final ManagementGuard guard;
    private final IdempotencyStore idem;
    private final AuditService audit;
    private final PageSizePolicy sizes;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public HikvisionSubscriptionService(JdbcTemplate jdbc, ObjectMapper json, HikvisionConfigService hikvision,
                                        HikvisionConfigSecrets secrets, HikvisionCallbackService callbacks,
                                        ManagementGuard guard, IdempotencyStore idem, AuditService audit,
                                        PageSizePolicy sizes, Clock clock, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.json = json;
        this.hikvision = hikvision;
        this.secrets = secrets;
        this.callbacks = callbacks;
        this.guard = guard;
        this.idem = idem;
        this.audit = audit;
        this.sizes = sizes;
        this.clock = clock;
        this.transactions = transactions;
    }

    // ---------------- inspect ----------------

    public Inspect inspect(AuthService.Identity actor, String requestId) {
        Capture capture = transactions.execute(status -> {
            guard.begin(actor, "integrations:write", null, "management");
            return capture();
        });
        if (capture == null) throw new IllegalStateException("Subscription inspection capture failed");
        RemoteList remote = readRemote(capture);
        return transactions.execute(status -> {
            guard.requireCurrent(actor, "integrations:write", null, "management");
            verifyCapture(capture);
            observe(capture, null, "INSPECT", remote);
            audit.record(actor.user().id(), null, "hikvision-subscription-inspect", null, "SUCCESS", requestId);
            return inspectView(capture, remote);
        });
    }

    // ---------------- preview ----------------

    public PreviewView preview(AuthService.Identity actor, String key, Inputs.Preview input, String requestId) {
        Capture capture = transactions.execute(status -> {
            guard.begin(actor, "integrations:write", null, "management");
            idem.validateKey(key);
            Capture current = capture();
            if (input.expectedReceiverRevision() != current.receiver().revision())
                throw ApiError.conflict("HIKVISION_RECEIVER_REVISION_CONFLICT", "事件推送配置已更新，请刷新后重新预览");
            if (input.expectedHikvisionRevision() != current.hikvisionRevision())
                throw ApiError.conflict("HIKVISION_CONFIGURATION_CONFLICT", "海康接入配置已更新，请刷新后重新预览");
            return current;
        });
        if (capture == null) throw new IllegalStateException("Subscription preview capture failed");
        if (!capture.receiver().enabled() || capture.receiver().origin() == null || !Objects.equals(capture.receiver().accountFingerprint(), capture.account()))
            throw ApiError.conflict("HIKVISION_RECEIVER_NOT_READY", "事件推送接收端未启用、缺少凭据或账号不一致；不会注册云端订阅");
        if (!supportedType(input.messageType())) throw ApiError.bad("只能对官方支持的消息类型操作");
        RemoteList remote = readRemote(capture);
        Target target = target(input.operation(), input.messageType(), remote, capture);
        String fingerprint = idem.fingerprint(List.of(input.operation(), input.messageType(),
            input.expectedReceiverRevision(), input.expectedHikvisionRevision(), input.reason()));
        PreviewView replay = idem.replay(actor.user().id(), "hikvision-subscription-preview", key, fingerprint, PreviewView.class);
        if (replay != null) return replay;
        if (target.noWriteRequired()) {
            return transactions.execute(status -> {
                guard.requireCurrent(actor, "integrations:write", null, "management");
                verifyCapture(capture);
                observe(capture, null, "PREVIEW", remote);
                audit.record(actor.user().id(), null, "hikvision-subscription-preview-noop", input.messageType(), "SUCCESS", requestId);
                PreviewView view = previewView(null, input.operation(), input.messageType(), "NO_WRITE_REQUIRED",
                    target, capture, target.note());
                idem.save(actor.user().id(), "hikvision-subscription-preview", key, fingerprint, view);
                return view;
            });
        }
        return transactions.execute(status -> {
            guard.requireCurrent(actor, "integrations:write", null, "management");
            verifyCapture(capture);
            String id = UUID.randomUUID().toString();
            String intendedCipher = null, intendedNonce = null;
            if (target.intendedUrl() != null) {
                var protectedUrl = secrets.protect(target.intendedUrl());
                intendedCipher = protectedUrl.cipher();
                intendedNonce = protectedUrl.nonce();
            }
            String requestDigest = Digests.sha256(operationRequestBody(input.operation(), input.messageType(), target));
            ObjectNode snapshot = json.createObjectNode();
            snapshot.put("endpoint", capture.runtime().endpoint());
            snapshot.put("app_key_masked", HikvisionConfigSecrets.mask(capture.runtime().appKey()));
            snapshot.put("account_fingerprint", capture.account());
            snapshot.put("hikvision_revision", capture.hikvisionRevision());
            snapshot.put("receiver_revision", capture.receiver().revision());
            snapshot.put("operation", input.operation()).put("message_type", input.messageType());
            snapshot.put("receiver_origin", capture.receiver().origin());
            snapshot.put("intended_origin", target.intendedOrigin());
            snapshot.put("intended_url_sha256", target.intendedSha());
            snapshot.put("previous_url_sha256", target.current() == null ? null : target.current().urlSha256());
            snapshot.put("remote_digest", target.remoteDigest()).put("remote_state", target.remoteState());
            snapshot.put("overwrite_required", target.overwriteRequired());
            snapshot.put("request_body_shape", DELETE.equals(input.operation()) ? "msgType" : "msgType+postUrl");
            snapshot.put("signed_body_digest", requestDigest);
            snapshot.put("vendor_cas_supported", false).put("vendor_retry_contract", false);
            OffsetDateTime now = OffsetDateTime.now(clock);
            try {
                jdbc.update("INSERT INTO hikvision_subscription_operations(id,operation,message_type,receiver_revision,hikvision_revision,account_fingerprint,intended_url_cipher,intended_url_nonce,intended_url_sha256,intended_origin,previous_url_sha256,previous_origin,remote_digest,remote_state,overwrite_required,overwrite_confirmed,request_digest,snapshot_json,state,revision,actor_id,reason,idem_key,request_hash,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREVIEW',0,?,?,?,?,?,?)",
                    id, input.operation(), input.messageType(), capture.receiver().revision(), capture.hikvisionRevision(),
                    capture.account(), intendedCipher, intendedNonce, target.intendedSha(), target.intendedOrigin(),
                    target.current() == null ? null : target.current().urlSha256(),
                    target.current() == null ? null : target.current().origin(), target.remoteDigest(),
                    target.remoteState(), target.overwriteRequired(), false, requestDigest, snapshot.toString(),
                    actor.user().id(), input.reason().trim(), key, fingerprint, now, now);
            } catch (DuplicateKeyException race) {
                throw ApiError.conflict("IDEMPOTENCY_CONFLICT", "该幂等键正在创建订阅意图，请刷新后查看");
            }
            observe(capture, id, "PREVIEW", remote);
            audit.recordReason(actor.user().id(), null, "hikvision-subscription-preview", id, "SUCCESS", requestId, input.reason());
            PreviewView view = previewView(id, input.operation(), input.messageType(), "PREVIEW", target, capture, target.note());
            view = new PreviewView(view.operationId(), view.operation(), view.messageType(), view.state(), false,
                view.currentOrigin(), view.intendedOrigin(), view.matchesReceiver(), view.overwriteRequired(),
                view.remoteDigest(), view.remoteState(), view.previousUrlSha256(), view.receiverRevision(),
                view.hikvisionRevision(), view.accountFingerprintMasked(),
                OffsetDateTime.now(clock).plusSeconds(PREVIEW_TTL_SECONDS), view.note(), view.limitations());
            idem.save(actor.user().id(), "hikvision-subscription-preview", key, fingerprint, view);
            return view;
        });
    }

    // ---------------- execute ----------------

    public OperationView execute(AuthService.Identity actor, String id, String key, Inputs.Execute input, String requestId) {
        Operation operation = transactions.execute(status -> {
            guard.begin(actor, "integrations:write", null, "management");
            idem.validateKey(key);
            Operation current = operation(id, true);
            if (!"PREVIEW".equals(current.state()))
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "只有未执行的预览可以提交；已发送/未知的意图只能核对，不能重发");
            if (current.createdAt().plusSeconds(PREVIEW_TTL_SECONDS).isBefore(OffsetDateTime.now(clock)))
                throw ApiError.conflict("HIKVISION_PREVIEW_EXPIRED", "预览已过期；请重新检查云端状态后生成新预览");
            if (!current.remoteDigest().equals(input.expectedRemoteDigest()))
                throw ApiError.conflict("HIKVISION_REMOTE_DIGEST_MISMATCH", "预览时的云端状态摘要不一致，请重新预览");
            Capture capture = capture();
            verifyOperationConfig(current, capture);
            if (current.overwriteRequired() && !input.overwriteConfirmed())
                throw ApiError.conflict("HIKVISION_OVERWRITE_CONFIRMATION_REQUIRED", "该操作会覆盖现有云端订阅地址，必须显式确认覆盖风险");
            return current;
        });
        if (operation == null) throw new IllegalStateException("Subscription execute preparation failed");
        String fingerprint = idem.fingerprint(List.of(id, operation.remoteDigest(), input.expectedRemoteDigest(), input.overwriteConfirmed()));
        OperationView replay = transactions.execute(status -> {
            guard.requireCurrent(actor, "integrations:write", null, "management");
            return idem.replay(actor.user().id(), "hikvision-subscription-execute", key, fingerprint, OperationView.class);
        });
        if (replay != null) return replay;
        Capture capture = transactions.execute(status -> capture());
        if (capture == null) throw new IllegalStateException("Subscription execute capture failed");
        verifyOperationConfig(operation, capture);
        String intendedUrl = intendedUrl(operation, capture);
        if (intendedUrl == null && !DELETE.equals(operation.operation()))
            throw ApiError.conflict("HIKVISION_RECEIVER_NOT_READY", "接收端凭据不可用，不能生成订阅目标");

        RemoteList preflight = readRemote(capture);
        String liveDigest = digestFor(operation.messageType(), preflight);
        if (!operation.remoteDigest().equals(liveDigest))
            throw ApiError.conflict("HIKVISION_REMOTE_STATE_CHANGED", "云端订阅在预览后已变化；未发起任何写入，请重新预览");
        boolean alreadyAbsent = DELETE.equals(operation.operation()) && rows(preflight, operation.messageType()).isEmpty();
        boolean alreadyIntended = !DELETE.equals(operation.operation()) && matches(rows(preflight, operation.messageType()), intendedUrl);

        HikvisionClient.Session session = transactions.execute(status -> {
            guard.requireCurrent(actor, "integrations:write", null, "management");
            Operation locked = operation(id, true);
            if (!"PREVIEW".equals(locked.state()))
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "订阅意图状态已变化，不能重复提交");
            Capture current = capture();
            verifyOperationConfig(locked, current);
            String currentUrl = intendedUrl(locked, current);
            if (!Objects.equals(sha256OrNull(currentUrl), locked.intendedUrlSha()))
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_INTENT_CHANGED", "接收端目标与预览时不一致，请重新预览");
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (alreadyAbsent || alreadyIntended) {
                jdbc.update("UPDATE hikvision_subscription_operations SET state='CONFIRMED',revision=revision+1,overwrite_confirmed=?,vendor_code=?,result_json=?,finished_at=?,updated_at=? WHERE id=? AND state='PREVIEW' AND revision=?",
                    input.overwriteConfirmed(), alreadyAbsent ? "ALREADY_ABSENT" : "ALREADY_CONFIGURED",
                    result("NO_WRITE_REQUIRED", null, alreadyAbsent ? "ALREADY_ABSENT" : "ALREADY_CONFIGURED").toString(), now, now,
                    id, locked.revision());
                attempt(id, "SUBMIT", "CONFIRMED", alreadyAbsent ? "ALREADY_ABSENT" : "ALREADY_CONFIGURED",
                    detail("云端状态已与目标一致，未发起写入"), now, now);
                observe(current, id, "READBACK", preflight);
                idem.save(actor.user().id(), "hikvision-subscription-execute", key, fingerprint, view(id));
                return null;
            }
            if (jdbc.update("UPDATE hikvision_subscription_operations SET state='IN_FLIGHT',revision=revision+1,overwrite_confirmed=?,started_at=?,updated_at=? WHERE id=? AND state='PREVIEW' AND revision=?",
                    input.overwriteConfirmed(), now, now, id, locked.revision()) != 1)
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "订阅意图状态已变化，不能重复提交");
            attempt(id, "SUBMIT", "IN_FLIGHT", null, detail("已持久化IN_FLIGHT，准备发起厂商写入"), now, null);
            return current.session();
        });
        if (session == null) {
            audit.record(actor.user().id(), null, "hikvision-subscription-execute-noop", id, "SUCCESS", requestId);
            return view(id);
        }
        HikvisionClient.VendorResult vendor;
        try {
            vendor = session.write(path(operation.operation()), body(operation, intendedUrl));
        } catch (HikvisionClient.Failure failure) {
            Outcome outcome = switch (failure.code()) {
                case "NOT_CONFIGURED", "WRITE_PATH_NOT_ALLOWED", "INVALID_REQUEST_BODY" ->
                    new Outcome("REJECTED", "ABORTED", failure.code(), result("NOT_SENT", null, failure.code()), "写入未发起");
                default -> new Outcome("UNKNOWN", "UNKNOWN", failure.code(), result("UNKNOWN", null, failure.code()), "厂商写入结果未确认；禁止自动重发");
            };
            return finish(actor, id, outcome, null, requestId, fingerprint, key);
        }
        Outcome outcome = classify(operation, vendor);
        RemoteList readback = null;
        boolean readbackFailed = false;
        try { readback = readRemote(capture); }
        catch (RuntimeException failure) { readbackFailed = true; }
        if (readback == null)
            outcome = new Outcome("UNKNOWN", "UNKNOWN", outcome.vendorCode(), result("READBACK_UNAVAILABLE", null, outcome.vendorCode()),
                "厂商写入已返回，但读取云端订阅失败；结果未确认，禁止自动重发");
        else if ("ACCEPTED".equals(outcome.attemptOutcome()))
            outcome = confirmFromReadback(operation, readback, outcome.vendorCode());
        return finish(actor, id, outcome, readback, requestId, fingerprint, key);
    }

    private OperationView finish(AuthService.Identity actor, String id, Outcome outcome, RemoteList observation,
                                 String requestId, String fingerprint, String key) {
        return transactions.execute(status -> {
            // The vendor side effect already happened under the authorized request; persist its result even if
            // authorization changed during I/O. No further external call is made here.
            ObservableOperation locked = lockOperation(id);
            Operation stored = operation(id, false);
            OffsetDateTime now = OffsetDateTime.now(clock);
            boolean terminal = List.of("CONFIRMED", "REJECTED").contains(outcome.state());
            if (jdbc.update("UPDATE hikvision_subscription_operations SET state=?,revision=revision+1,vendor_code=?,result_json=?,finished_at=?,updated_at=? WHERE id=? AND state='IN_FLIGHT' AND revision=?",
                    outcome.state(), outcome.vendorCode(), outcome.result() == null ? null : outcome.result().toString(),
                    terminal ? now : null, now, id, locked.revision()) != 1)
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "订阅意图状态已变化，厂商结果未写入；请显式核对");
            attempt(id, "READBACK", outcome.attemptOutcome(), outcome.vendorCode(), detail(outcome.note()), now, now);
            if (observation != null) observe(stored.accountFingerprint(), stored.hikvisionRevision(), id, "READBACK", observation);
            audit.recordReason(actor.user().id(), null, "hikvision-subscription-execute", id, outcome.state(), requestId, outcome.note());
            OperationView view = view(id);
            idem.save(actor.user().id(), "hikvision-subscription-execute", key, fingerprint, view);
            return view;
        });
    }

    // ---------------- reconcile / cancel / reads ----------------

    public OperationView reconcile(AuthService.Identity actor, String id, String key, String requestId) {
        OperationView replay = transactions.execute(status -> {
            guard.begin(actor, "integrations:write", null, "management");
            idem.validateKey(key);
            return idem.replay(actor.user().id(), "hikvision-subscription-reconcile", key,
                idem.fingerprint(List.of(id)), OperationView.class);
        });
        if (replay != null) return replay;
        Operation operation = transactions.execute(status -> operation(id, false));
        if (operation == null) throw ApiError.missing();
        if (!List.of("IN_FLIGHT", "PENDING_CONFIRMATION", "UNKNOWN").contains(operation.state()))
            throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "只有已发送、待确认或结果未知的订阅意图可以核对");
        Capture capture = transactions.execute(status -> {
            guard.requireCurrent(actor, "integrations:write", null, "management");
            return capture();
        });
        if (capture == null) throw new IllegalStateException("Subscription reconcile capture failed");
        if (!Objects.equals(operation.accountFingerprint(), capture.account()))
            throw ApiError.conflict("HIKVISION_ACCOUNT_CHANGED", "海康账号已轮换，不能用其他账号核对旧订阅意图");
        RemoteList remote = readRemote(capture);
        Outcome outcome = reconcileOutcome(operation, remote);
        return transactions.execute(status -> {
            guard.requireCurrent(actor, "integrations:write", null, "management");
            ObservableOperation locked = lockOperation(id);
            OffsetDateTime now = OffsetDateTime.now(clock);
            String next = outcome.state();
            boolean terminal = List.of("CONFIRMED", "REJECTED").contains(next);
            if (!"PENDING_CONFIRMATION".equals(next) && !"UNKNOWN".equals(next)) terminal = true;
            if (jdbc.update("UPDATE hikvision_subscription_operations SET state=?,revision=revision+1,vendor_code=?,result_json=?,finished_at=?,updated_at=? WHERE id=? AND revision=?",
                    next, outcome.vendorCode(), outcome.result() == null ? null : outcome.result().toString(),
                    terminal ? now : null, now, id, locked.revision()) != 1)
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "订阅意图状态已变化，请刷新");
            attempt(id, "RECONCILE", outcome.attemptOutcome(), outcome.vendorCode(), detail(outcome.note()), now, now);
            observe(capture, id, "RECONCILE", remote);
            audit.recordReason(actor.user().id(), null, "hikvision-subscription-reconcile", id, next, requestId, outcome.note());
            OperationView view = view(id);
            idem.save(actor.user().id(), "hikvision-subscription-reconcile", key, idem.fingerprint(List.of(id)), view);
            return view;
        });
    }

    public OperationView cancel(AuthService.Identity actor, String id, String key, Inputs.Cancel input, String requestId) {
        return transactions.execute(status -> {
            guard.begin(actor, "integrations:write", null, "management");
            idem.validateKey(key);
            OperationView replay = idem.replay(actor.user().id(), "hikvision-subscription-cancel", key,
                idem.fingerprint(List.of(id, input.reason())), OperationView.class);
            if (replay != null) return replay;
            ObservableOperation operation = lockOperation(id);
            if (!"PREVIEW".equals(operation.state()))
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_STATE", "只有尚未提交的预览可以取消；已发送的意图必须核对");
            OffsetDateTime now = OffsetDateTime.now(clock);
            jdbc.update("UPDATE hikvision_subscription_operations SET state='CANCELLED',revision=revision+1,finished_at=?,updated_at=? WHERE id=? AND state='PREVIEW' AND revision=?",
                now, now, id, operation.revision());
            attempt(id, "PREVIEW", "CANCELLED", null, detail(input.reason().trim()), now, now);
            audit.recordReason(actor.user().id(), null, "hikvision-subscription-cancel", id, "SUCCESS", requestId, input.reason());
            OperationView view = view(id);
            idem.save(actor.user().id(), "hikvision-subscription-cancel", key, idem.fingerprint(List.of(id, input.reason())), view);
            return view;
        });
    }

    public Page<OperationSummary> operations(int page, Integer size) {
        var pagination = Pagination.of(page, size, sizes.defaultPageSize());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM hikvision_subscription_operations", Long.class);
        var items = jdbc.query("SELECT * FROM hikvision_subscription_operations ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, n) -> summary(operationRow(rs)), pagination.size(), pagination.offset());
        return new Page<>(items, total, pagination.page(), pagination.size());
    }

    public OperationView view(String id) {
        Operation operation = operation(id, false);
        List<AttemptView> attempts = jdbc.query("SELECT * FROM hikvision_subscription_attempts WHERE operation_id=? ORDER BY attempt_no",
            (rs, n) -> new AttemptView(rs.getString("id"), rs.getInt("attempt_no"), rs.getString("phase"),
                rs.getString("outcome"), rs.getString("vendor_code"), parseNullable(rs.getString("detail_json")),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)), id);
        List<ObservationView> observations = jdbc.query("SELECT * FROM hikvision_subscription_observations WHERE operation_id=? ORDER BY observed_at,id",
            (rs, n) -> new ObservationView(rs.getString("id"), rs.getString("trigger_kind"), rs.getString("list_digest"),
                parseNullable(rs.getString("snapshot_json")), rs.getObject("observed_at", OffsetDateTime.class)), id);
        boolean matches = operation.intendedUrlSha() != null && operation.previousUrlSha() != null
            && operation.intendedUrlSha().equals(operation.previousUrlSha());
        return new OperationView(operation.id(), operation.operation(), operation.messageType(), operation.state(),
            operation.revision(), operation.intendedOrigin(), operation.previousOrigin(), matches,
            operation.overwriteRequired(), operation.overwriteConfirmed(), operation.remoteDigest(), operation.remoteState(),
            operation.vendorCode(), parseNullable(operation.resultJson()), mask(operation.accountFingerprint()),
            operation.actorId(), operation.reason(), operation.createdAt(), operation.startedAt(), operation.finishedAt(),
            operation.createdAt().plusSeconds(PREVIEW_TTL_SECONDS), attempts, observations);
    }

    // ---------------- vendor read/classification ----------------

    private Capture capture() {
        HikvisionConfigService.Runtime runtime = hikvision.refreshRuntime();
        String account;
        try {
            account = runtime.session().configured() && secrets.ready()
                ? secrets.fingerprint(runtime.endpoint() + "\n" + runtime.appKey()) : null;
        } catch (RuntimeException unavailable) { account = null; }
        if (account == null) throw new ApiError(503, "HIKVISION_NOT_CONFIGURED", "海康凭证未配置");
        HikvisionCallbackService.ReceiverConfig receiver = callbacks.receiverForSubscription();
        String url = receiver.origin() == null || receiver.credential() == null ? null
            : receiver.origin() + HikvisionCallbackService.RECEIVER_PATH.replace("{credential}", receiver.credential());
        return new Capture(new Receiver(receiver.origin(), url, receiver.accountFingerprint(), receiver.revision(), receiver.enabled()),
            runtime, account, runtime.revision(), runtime.session());
    }

    private void verifyCapture(Capture capture) {
        HikvisionConfigService.Runtime runtime = hikvision.refreshRuntime();
        HikvisionCallbackService.ReceiverConfig receiver = callbacks.receiverForSubscription();
        String account;
        try {
            account = runtime.session().configured() && secrets.ready()
                ? secrets.fingerprint(runtime.endpoint() + "\n" + runtime.appKey()) : null;
        } catch (RuntimeException unavailable) { account = null; }
        if (!Objects.equals(account, capture.account()) || runtime.revision() != capture.hikvisionRevision()
            || receiver.revision() != capture.receiver().revision()
            || !Objects.equals(receiver.origin(), capture.receiver().origin())
            || !Objects.equals(receiver.accountFingerprint(), capture.receiver().accountFingerprint()))
            throw ApiError.conflict("HIKVISION_CONFIGURATION_CHANGED", "海康或接收端配置在操作期间变化，请重新预览");
    }

    private void verifyOperationConfig(Operation operation, Capture capture) {
        if (!Objects.equals(operation.accountFingerprint(), capture.account())
            || operation.hikvisionRevision() != capture.hikvisionRevision()
            || operation.receiverRevision() != capture.receiver().revision())
            throw ApiError.conflict("HIKVISION_CONFIGURATION_CHANGED", "海康账号或配置已变化；旧订阅意图不能静默改用新账号发送");
    }

    private RemoteList readRemote(Capture capture) {
        List<Row> rows = new ArrayList<>();
        String receiverUrl = capture.receiver().url();
        for (JsonNode item : capture.session().readSubscriptions()) {
            String type = item.path("msgType").asText("");
            if (!type.matches("[0-9]{1,16}")) continue;
            String url = item.path("postUrl").asText("");
            String sha = url.isBlank() ? null : Digests.sha256(url);
            rows.add(new Row(type, url, originOf(url), receiverUrl != null && receiverUrl.equals(url), sha,
                parseTime(item.path("updateTime").asText(null))));
        }
        rows.sort(Comparator.comparing(Row::messageType).thenComparing(row -> row.urlSha256() == null ? "" : row.urlSha256()));
        Map<String, List<Row>> byType = new LinkedHashMap<>();
        for (Row row : rows) byType.computeIfAbsent(row.messageType(), ignored -> new ArrayList<>()).add(row);
        Map<String, String> digests = new LinkedHashMap<>();
        for (String type : SUPPORTED) digests.put(type, digestFor(type, byType.getOrDefault(type, List.of())));
        return new RemoteList(OffsetDateTime.now(clock), List.copyOf(rows), byType, Map.copyOf(digests));
    }

    private Target target(String operation, String messageType, RemoteList remote, Capture capture) {
        List<Row> rows = rows(remote, messageType);
        if (rows.size() > 1)
            throw ApiError.conflict("HIKVISION_SUBSCRIPTION_AMBIGUOUS", "云端同一消息类型存在多条订阅，需人工核对后再操作");
        Row current = rows.isEmpty() ? null : rows.getFirst();
        String intendedUrl = capture.receiver().url();
        String intendedSha = sha256OrNull(intendedUrl);
        boolean matches = current != null && Objects.equals(current.urlSha256(), intendedSha);
        boolean noWrite = false;
        String note;
        if (ADD.equals(operation)) {
            if (current != null && !matches)
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_EXISTS_USE_UPDATE", "该消息类型已存在其他地址；新增会覆盖，请使用显式更新并确认覆盖风险");
            noWrite = matches;
            note = matches ? "云端该消息类型已指向当前接收端，无需写入" : "新增该消息类型的接收端订阅";
        } else if (UPDATE.equals(operation)) {
            if (current == null)
                throw ApiError.conflict("HIKVISION_SUBSCRIPTION_NOT_FOUND_USE_ADD", "该消息类型尚不存在云端订阅；请使用新增");
            noWrite = matches;
            note = matches ? "云端该消息类型已指向当前接收端，无需更新" : "更新会覆盖该消息类型现有地址";
        } else if (DELETE.equals(operation)) {
            noWrite = current == null;
            note = noWrite ? "该消息类型云端已不存在，无需删除" : "删除该消息类型的云端订阅";
        } else throw ApiError.bad("订阅操作无效");
        String remoteDigest = digestFor(messageType, rows);
        String remoteState = current == null ? "ABSENT" : "PRESENT";
        boolean overwriteRequired = !noWrite && !matches && current != null;
        return new Target(messageType, current, intendedUrl, originOf(intendedUrl), intendedSha, remoteDigest,
            remoteState, overwriteRequired, noWrite, note);
    }

    private Outcome classify(Operation operation, HikvisionClient.VendorResult vendor) {
        ObjectNode result = json.createObjectNode();
        result.put("vendor_code", vendor.code());
        result.put("write", "ACCEPTED_BY_VENDOR");
        result.put("terminal_effect_proven", false);
        if (!"0".equals(vendor.code())) {
            if (DELETE.equals(operation.operation()) && "0x00082102".equals(vendor.code()))
                return new Outcome("IN_FLIGHT", "ACCEPTED", vendor.code(), result,
                    "厂商表示订阅不存在；等待只读回读确认");
            if (ADD.equals(operation.operation()) && "0x00082101".equals(vendor.code()))
                return new Outcome("IN_FLIGHT", "ACCEPTED", vendor.code(), result,
                    "厂商表示已订阅；等待只读回读确认地址是否一致");
            if (vendor.code().matches("0x[0-9A-Fa-f]{8}"))
                return new Outcome("REJECTED", "REJECTED", vendor.code(), result, "厂商拒绝订阅写入");
            return new Outcome("UNKNOWN", "UNKNOWN", vendor.code(), result, "厂商返回非标准错误码，写入结果未确认");
        }
        return new Outcome("IN_FLIGHT", "ACCEPTED", vendor.code(), result, "厂商接受；等待只读回读确认");
    }

    private Outcome confirmFromReadback(Operation operation, RemoteList remote, String vendorCode) {
        List<Row> rows = rows(remote, operation.messageType());
        ObjectNode result = json.createObjectNode();
        result.put("vendor_code", vendorCode);
        result.put("readback_state", rows.isEmpty() ? "ABSENT" : "PRESENT");
        result.put("readback_origin", rows.isEmpty() ? null : rows.getFirst().origin());
        result.put("readback_sha256", rows.isEmpty() ? null : rows.getFirst().urlSha256());
        if (DELETE.equals(operation.operation())) {
            return rows.isEmpty()
                ? new Outcome("CONFIRMED", "CONFIRMED", vendorCode, result, "只读回读确认该消息类型订阅已不存在")
                : new Outcome("PENDING_CONFIRMATION", "PENDING", vendorCode, result,
                    "厂商已接受删除，但云订阅列表仍存在该类型；可能是最终一致性，保持待确认而非失败");
        }
        if (rows.isEmpty())
            return new Outcome("PENDING_CONFIRMATION", "PENDING", vendorCode, result,
                "厂商已接受写入，但回读尚未看到该订阅；可能是最终一致性，保持待确认而非失败");
        if (rows.size() > 1)
            return new Outcome("UNKNOWN", "UNKNOWN", vendorCode, result, "回读出现多条订阅，需人工核对");
        Row row = rows.getFirst();
        String intendedSha = operation.intendedUrlSha();
        if (row.urlSha256() != null && row.urlSha256().equals(intendedSha))
            return new Outcome("CONFIRMED", "CONFIRMED", vendorCode, result, "只读回读确认目标地址与当前接收端完全一致");
        if (row.urlSha256() == null || row.urlSha256().equals(operation.previousUrlSha())
            || operation.previousUrlSha() == null && row.origin() == null)
            return new Outcome("PENDING_CONFIRMATION", "PENDING", vendorCode, result,
                "厂商已接受写入，但回读仍是旧/缺失状态；可能是最终一致性，保持待确认而非失败");
        return new Outcome("UNKNOWN", "UNKNOWN", vendorCode, result, "回读地址与目标及原状态均不一致，需人工核对");
    }

    private Outcome reconcileOutcome(Operation operation, RemoteList remote) {
        List<Row> rows = rows(remote, operation.messageType());
        ObjectNode result = json.createObjectNode();
        result.put("readback_state", rows.isEmpty() ? "ABSENT" : "PRESENT");
        result.put("readback_origin", rows.isEmpty() ? null : rows.getFirst().origin());
        result.put("readback_sha256", rows.isEmpty() ? null : rows.getFirst().urlSha256());
        if (DELETE.equals(operation.operation()))
            return rows.isEmpty()
                ? new Outcome("CONFIRMED", "CONFIRMED", "READBACK_ABSENT", result, "只读核对确认订阅已不存在")
                : new Outcome("PENDING_CONFIRMATION", "PENDING", "READBACK_PRESENT", result,
                    "只读核对仍看到该订阅；保持待确认，不视为失败，也不会自动重发");
        if (rows.isEmpty())
            return new Outcome("PENDING_CONFIRMATION", "PENDING", "READBACK_ABSENT", result,
                "只读核对尚未看到该订阅；保持待确认，不视为失败，也不会自动重发");
        if (rows.size() > 1)
            return new Outcome("UNKNOWN", "UNKNOWN", "READBACK_AMBIGUOUS", result, "只读核对出现多条订阅，需人工核对");
        Row row = rows.getFirst();
        String intendedSha = operation.intendedUrlSha();
        if (row.urlSha256() != null && row.urlSha256().equals(intendedSha))
            return new Outcome("CONFIRMED", "CONFIRMED", "READBACK_MATCH", result, "只读核对确认目标地址一致");
        if (operation.previousUrlSha() != null && Objects.equals(row.urlSha256(), operation.previousUrlSha()))
            return new Outcome("PENDING_CONFIRMATION", "PENDING", "READBACK_OLD_STATE", result,
                "只读核对仍为原地址；保持待确认，不视为失败，也不会自动重发");
        if (row.origin() == null)
            return new Outcome("PENDING_CONFIRMATION", "PENDING", "READBACK_UNRECOGNIZED", result,
                "只读核对无法安全识别现有地址；保持待确认供人工核对");
        return new Outcome("UNKNOWN", "UNKNOWN", "READBACK_DIVERGED", result, "只读核对地址与目标及原状态均不一致，需人工处置");
    }

    // ---------------- helpers ----------------

    private Inspect inspectView(Capture capture, RemoteList remote) {
        List<RemoteRow> items = new ArrayList<>();
        for (Row row : remote.rows())
            items.add(new RemoteRow(row.messageType(), row.origin(), row.matchesReceiver(), row.urlSha256(),
                row.updatedAt(), supportedType(row.messageType()), rows(remote, row.messageType()).size() > 1));
        List<RemoteRow> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(RemoteRow::messageType).thenComparing(row -> row.urlSha256() == null ? "" : row.urlSha256()));
        return new Inspect(remote.observedAt(), capture.receiver().revision(), capture.hikvisionRevision(),
            mask(capture.account()), capture.receiver().origin(),
            capture.receiver().enabled() && capture.receiver().url() != null
                && Objects.equals(capture.receiver().accountFingerprint(), capture.account()),
            List.copyOf(sorted), Map.copyOf(remote.digests()), limitations());
    }

    private PreviewView previewView(String id, String operation, String messageType, String state, Target target,
                                    Capture capture, String note) {
        return new PreviewView(id, operation, messageType, state, target.noWriteRequired(),
            target.current() == null ? null : target.current().origin(), target.intendedOrigin(),
            target.current() != null && Objects.equals(target.current().urlSha256(), target.intendedSha()),
            target.overwriteRequired(), target.remoteDigest(), target.remoteState(),
            target.current() == null ? null : target.current().urlSha256(), capture.receiver().revision(),
            capture.hikvisionRevision(), mask(capture.account()),
            OffsetDateTime.now(clock).plusSeconds(PREVIEW_TTL_SECONDS), note, limitations());
    }

    private void observe(Capture capture, String operationId, String trigger, RemoteList remote) {
        observe(capture.account(), capture.hikvisionRevision(), operationId, trigger, remote);
    }

    private void observe(String account, long hikvisionRevision, String operationId, String trigger, RemoteList remote) {
        ObjectNode snapshot = json.createObjectNode();
        ArrayNode items = snapshot.putArray("items");
        for (Row row : remote.rows()) {
            ObjectNode item = items.addObject();
            item.put("message_type", row.messageType()).put("destination_origin", row.origin())
                .put("matches_receiver", row.matchesReceiver()).put("url_sha256", row.urlSha256())
                .put("supported", supportedType(row.messageType()))
                .put("updated_at", row.updatedAt() == null ? null : row.updatedAt().toString());
            if (rows(remote, row.messageType()).size() > 1) item.put("duplicate", true);
        }
        snapshot.set("digests", json.valueToTree(remote.digests()));
        snapshot.put("raw_urls_included", false);
        jdbc.update("INSERT INTO hikvision_subscription_observations(id,operation_id,account_fingerprint,hikvision_revision,trigger_kind,list_digest,snapshot_json,observed_at) VALUES(?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), operationId, account, hikvisionRevision, trigger,
            Digests.sha256(canonicalDigests(remote.digests())), snapshot.toString(), remote.observedAt());
    }

    private void attempt(String operationId, String phase, String outcome, String vendorCode, String detail,
                         OffsetDateTime startedAt, OffsetDateTime finishedAt) {
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_no),0)+1 FROM hikvision_subscription_attempts WHERE operation_id=?",
            Integer.class, operationId);
        jdbc.update("INSERT INTO hikvision_subscription_attempts(id,operation_id,attempt_no,phase,outcome,vendor_code,detail_json,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(), operationId, next == null ? 1 : next, phase, outcome, vendorCode, detail,
            startedAt, finishedAt);
    }

    private String intendedUrl(Operation operation, Capture capture) {
        if (DELETE.equals(operation.operation())) return null;
        return capture.receiver().url();
    }

    private static List<Row> rows(RemoteList remote, String messageType) {
        return remote.byType().getOrDefault(messageType, List.of());
    }

    private static boolean matches(List<Row> rows, String intendedUrl) {
        if (intendedUrl == null || rows.size() != 1) return false;
        return intendedUrl.equals(rows.getFirst().url());
    }

    private static String canonicalDigests(Map<String, String> digests) {
        return String.join("\n", new java.util.TreeMap<>(digests).entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue()).toList());
    }

    private static String digestFor(String messageType, RemoteList remote) {
        return digestFor(messageType, rows(remote, messageType));
    }

    private static String digestFor(String messageType, List<Row> rows) {
        if (rows.isEmpty()) return Digests.sha256(messageType + "\nABSENT");
        if (rows.size() == 1) return Digests.sha256(messageType + "\n" + rows.getFirst().url());
        var values = rows.stream().map(Row::url).sorted().toList();
        return Digests.sha256(messageType + "\nDUP\n" + String.join("\n", values));
    }

    private static String operationRequestBody(String operation, String messageType, Target target) {
        return Digests.sha256(operation + "\n" + messageType + "\n"
            + (DELETE.equals(operation) ? "DELETE" : target.intendedSha()));
    }

    private static String path(String operation) {
        return switch (operation) {
            case ADD -> "/api/subscription/v2/add";
            case UPDATE -> "/api/subscription/v2/update";
            case DELETE -> "/api/subscription/v2/delete";
            default -> throw ApiError.bad("订阅操作无效");
        };
    }

    private JsonNode body(Operation operation, String intendedUrl) {
        ObjectNode body = json.createObjectNode();
        body.put("msgType", operation.messageType());
        if (!DELETE.equals(operation.operation())) body.put("postUrl", intendedUrl);
        return body;
    }

    private static String sha256OrNull(String value) {
        return value == null ? null : Digests.sha256(value);
    }

    private static Limitation limitations() {
        return new Limitation(false, false, false,
            "厂商订阅API未发布CAS、幂等键、重试/时序一致性或投递SLA；读-写之间存在并发覆盖窗口，code0仅表示厂商接受，需只读回读确认。");
    }

    private static String mask(String fingerprint) {
        if (fingerprint == null) return null;
        return "****" + (fingerprint.length() <= 8 ? fingerprint : fingerprint.substring(fingerprint.length() - 8));
    }

    private static final java.util.regex.Pattern SUPPORTED_TYPE_PATTERN =
        java.util.regex.Pattern.compile("(" + String.join("|", SUPPORTED) + ")");
    private static boolean supportedType(String value) { return SUPPORTED_TYPE_PATTERN.matcher(value).matches(); }

    private static String originOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return "https://" + host + (uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        } catch (RuntimeException invalid) { return null; }
    }

    private static OffsetDateTime parseTime(String value) {
        try { return value == null || value.isBlank() ? null : OffsetDateTime.parse(value); }
        catch (RuntimeException invalid) { return null; }
    }

    private String detail(String note) {
        ObjectNode node = json.createObjectNode();
        node.put("note", note);
        return node.toString();
    }

    private ObjectNode result(String phase, JsonNode extra, String code) {
        ObjectNode node = json.createObjectNode();
        node.put("phase", phase);
        node.put("code", code);
        node.put("terminal_effect_proven", false);
        if (extra != null) node.set("detail", extra);
        return node;
    }

    private JsonNode parseNullable(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return json.readTree(raw); } catch (Exception invalid) { return null; }
    }

    private Operation operation(String id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM hikvision_subscription_operations WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (rs, n) -> operationRow(rs), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private record ObservableOperation(String id, String state, long revision) { }
    private ObservableOperation lockOperation(String id) {
        var rows = jdbc.query("SELECT id,state,revision FROM hikvision_subscription_operations WHERE id=? FOR UPDATE",
            (rs, n) -> new ObservableOperation(rs.getString(1), rs.getString(2), rs.getLong(3)), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }

    private static Operation operationRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Operation(rs.getString("id"), rs.getString("operation"), rs.getString("message_type"),
            rs.getLong("receiver_revision"), rs.getLong("hikvision_revision"), rs.getString("account_fingerprint"),
            rs.getString("intended_url_sha256"), rs.getString("intended_origin"), rs.getString("previous_url_sha256"),
            rs.getString("previous_origin"), rs.getString("remote_digest"), rs.getString("remote_state"),
            rs.getBoolean("overwrite_required"), rs.getBoolean("overwrite_confirmed"), rs.getString("request_digest"),
            rs.getString("state"), rs.getLong("revision"), rs.getString("actor_id"), rs.getString("reason"),
            rs.getString("vendor_code"), rs.getString("result_json"), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static OperationSummary summary(Operation operation) {
        boolean matches = operation.intendedUrlSha() != null && operation.intendedUrlSha().equals(operation.previousUrlSha());
        return new OperationSummary(operation.id(), operation.operation(), operation.messageType(), operation.state(),
            operation.revision(), operation.intendedOrigin(), operation.previousOrigin(), matches,
            operation.overwriteRequired(), operation.vendorCode(), operation.actorId(), operation.createdAt(),
            operation.finishedAt());
    }

    public static final class Inputs {
        private Inputs() { }
        public record Preview(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "ADD|UPDATE|DELETE") String operation,
                              @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 16) String messageType,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedReceiverRevision,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Long expectedHikvisionRevision,
                              @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String reason) { }
        public record Execute(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64) String expectedRemoteDigest,
                              boolean overwriteConfirmed) { }
        public record Cancel(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String reason) { }
    }
}
