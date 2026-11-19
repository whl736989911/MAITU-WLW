package com.fireplatform;

import com.fasterxml.jackson.databind.*;
import com.fireplatform.shared.*;
import com.fireplatform.simbinding.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SimBindingInputsTest {
    final ObjectMapper json=new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    final SimBindingInputs inputs=new SimBindingInputs(json);
    Map<String,Object> body(){var body=new LinkedHashMap<String,Object>();body.put("device_id","legacy-device");body.put("expected_association_revision",0L);body.put("reason","");return body;}
    SimBindingInputs.Command parse(Map<String,Object> body)throws Exception{return inputs.command(json.writeValueAsBytes(body));}
    void bad(org.assertj.core.api.ThrowableAssert.ThrowingCallable work){assertThatThrownBy(work).isInstanceOfSatisfying(ApiError.class,e->{assertThat(e.status).isEqualTo(400);assertThat(e.code).isEqualTo("INVALID_REQUEST");assertThat(e.getMessage()).doesNotContain("SECRET");});}
    @Test void fourRequiredNonNullFieldsNeverAcceptUnknownOrMissingKeys()throws Exception{assertThat(parse(body()).deviceId()).isEqualTo("legacy-device");for(String key:body().keySet()){var missing=body();missing.remove(key);bad(()->parse(missing));var nil=body();nil.put(key,null);bad(()->parse(nil));}for(String key:List.of("tenant_id","card_id","revision","device_name","iccid","changed","operation")){var extra=body();extra.put(key,"SECRET");bad(()->parse(extra));}}
    @Test void scalarTypesNeverCoerceIntoStringsOrRevisions()throws Exception{for(String key:List.of("device_id","reason"))for(Object value:List.of(1,true,List.of("x"),Map.of("x","y"))){var body=body();body.put(key,value);bad(()->parse(body));}for(Object value:List.of("0",true,List.of(0),Map.of("n",0))){var body=body();body.put("expected_association_revision",value);bad(()->parse(body));}}
    @Test void rawRevisionGrammarRejectsNegativeZeroFractionExponentUnsafeAndNearZero()throws Exception{String valid=json.writeValueAsString(body());for(String number:List.of("-0","-1","0.0","0e0","0.00000000000000000001","9007199254740992","9223372036854775808"))bad(()->inputs.command(valid.replace("\"expected_association_revision\":0","\"expected_association_revision\":"+number).getBytes(StandardCharsets.UTF_8)));}
    @Test void zeroAndMaximumSafePositiveRevisionRemainExact()throws Exception{assertThat(parse(body()).expectedAssociationRevision()).isZero();var body=body();body.put("expected_association_revision",SimBindingInputs.MAX_SAFE);assertThat(parse(body).expectedAssociationRevision()).isEqualTo(SimBindingInputs.MAX_SAFE);}
    @Test void duplicateTrailingRootArrayNullAndMalformedEscapesFail()throws Exception{String valid=json.writeValueAsString(body());for(String raw:List.of("null","[]","{}",valid+" {}",valid.substring(0,valid.length()-1)+",\"reason\":\"SECRET\"}",valid.replace("legacy-device","bad\\q")))bad(()->inputs.command(raw.getBytes(StandardCharsets.UTF_8)));}
    @Test void deviceIsAnExactOpaqueIdNotUuidNumberOrNormalizedText(){for(String id:List.of("legacy-device","000123","Ａ１２","device/opaque?%#","\u00a0id\u00a0","\ufeffid\ufeff","\u3000id\u3000"))assertThat(SimBindingInputs.device(id)).isEqualTo(id);assertThat(SimBindingInputs.device("Case")).isNotEqualTo(SimBindingInputs.device("case"));}
    @Test void onlyJavaTrimEdgesAreRejectedWithoutSilentlyChangingDeviceIdentity(){for(String id:List.of(" id","id ","  ","\tid","id\n"))bad(()->SimBindingInputs.device(id));}
    @Test void everyDeviceControlAndInvalidUtf16IsRejectedBeforeAnyNormalization(){for(String id:Arrays.asList(null,"","a\u0000b","a\tb","a\nb","a\u007fb","a\u0085b","\ud800","\udc00"))bad(()->SimBindingInputs.device(id));}
    @Test void deviceTechnicalLimitCountsUtf16Units(){assertThat(SimBindingInputs.device("😀".repeat(32))).hasSize(64);bad(()->SimBindingInputs.device("😀".repeat(33)));bad(()->SimBindingInputs.device("x".repeat(65)));}
    @Test void reasonRetainsAllLegalOriginalWhitespaceAndIsNotMandatory(){for(String reason:List.of(""," "," \r\n\ta\t\r\n ","\u00a0","<literal>&text"))assertThat(SimBindingInputs.reason(reason)).isEqualTo(reason);}
    @Test void reasonAllowsOnlyCrLfTabControlsAndValidUtf16(){for(String reason:Arrays.asList(null,"a\u0000b","\u000b","\u001f","\u007f","\u0085","\ud800"))bad(()->SimBindingInputs.reason(reason));}
    @Test void reasonUsesExistingCentralUtf16LimitWithoutNewBusinessThreshold(){assertThat(SimBindingInputs.reason("😀".repeat(500))).hasSize(Limits.MAX_CHANGE_REASON_LENGTH);bad(()->SimBindingInputs.reason("😀".repeat(501)));}
    @Test void actorConfirmationPasswordFieldIsRejected()throws Exception{var body=body();body.put("actor_password","Synthetic-Confirmation-Password");bad(()->parse(body));}
    @Test void pagingHasCanonicalRawIntegerGrammarAndNoCoercion(){assertThat(SimBindingInputs.page(null,1)).isEqualTo(1);assertThat(SimBindingInputs.page("1000000",1)).isEqualTo(1000000);for(String value:List.of("-0","01","-1","1.0","1e0","2147483648",""))bad(()->SimBindingInputs.page(value,1));}
}
