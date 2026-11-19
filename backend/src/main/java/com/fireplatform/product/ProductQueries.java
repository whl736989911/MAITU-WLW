package com.fireplatform.product;

import com.fireplatform.shared.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductQueries implements PublishedVersionCatalog {
    private static final Set<String> STATES=Set.of("ACTIVE","ARCHIVED","ALL");
    private final JdbcTemplate jdbc; private final PageSizePolicy sizes; private final IdempotencyStore json;
    public ProductQueries(JdbcTemplate jdbc,PageSizePolicy sizes,IdempotencyStore json) { this.jdbc=jdbc; this.sizes=sizes; this.json=json; }
    private ProductModels.Product row(ResultSet rs) throws SQLException { return new ProductModels.Product(rs.getString("id"),rs.getString("name"),rs.getString("manufacturer"),rs.getString("model"),rs.getString("description"),rs.getLong("revision"),rs.getString("state"),rs.getObject("archived_at",OffsetDateTime.class)); }
    public ProductModels.Product product(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM products WHERE id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->row(rs),id); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    @Transactional(readOnly=true) public Page<ProductModels.Product> products(int page,Integer size,String keyword,String state) {
        var p=Pagination.of(page,size,sizes.defaultPageSize()); if(keyword==null) keyword="";
        String status=state==null||state.isBlank()?"ACTIVE":state.trim().toUpperCase(Locale.ROOT);
        if(!STATES.contains(status)) throw ApiError.bad("产品状态筛选无效");
        if(keyword.length()>Limits.MAX_KEYWORD_LENGTH) throw ApiError.bad("搜索关键字过长");
        String like="%"+keyword.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        String where=" FROM products WHERE (LOWER(name) LIKE ? ESCAPE '!' OR LOWER(manufacturer) LIKE ? ESCAPE '!' OR LOWER(model) LIKE ? ESCAPE '!')";
        if(!"ALL".equals(status)) where+=" AND state=?";
        long total="ALL".equals(status)?jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,like,like,like)
            :jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,like,like,like,status);
        List<ProductModels.Product> items;
        if("ALL".equals(status)) items=jdbc.query("SELECT *"+where+" ORDER BY id LIMIT ? OFFSET ?",(rs,n)->row(rs),like,like,like,p.size(),p.offset());
        else items=jdbc.query("SELECT *"+where+" ORDER BY id LIMIT ? OFFSET ?",(rs,n)->row(rs),like,like,like,status,p.size(),p.offset());
        return new Page<>(items,total,p.page(),p.size());
    }
    private ProductModels.Version versionRow(ResultSet rs) throws SQLException {
        String summary=rs.getString("test_summary_json"); java.math.BigDecimal threshold=rs.getBigDecimal("battery_low_threshold");
        var battery=threshold==null ? null : new ProductModels.BatteryThreshold("battery_low_threshold_percent","battery_percent","%",rs.getString("battery_low_comparator"),threshold);
        return new ProductModels.Version(rs.getString("id"),rs.getString("product_id"),rs.getLong("version_number"),rs.getString("status"),rs.getLong("revision"),json.parse(rs.getString("schema_json"),com.fasterxml.jackson.databind.JsonNode.class),json.parse(rs.getString("capabilities_json"),com.fasterxml.jackson.databind.JsonNode.class),battery,rs.getBoolean("parser_tested"),rs.getObject("published_at",OffsetDateTime.class),summary==null ? null : json.parse(summary,ProductModels.TestSummary.class),rs.getObject("created_at",OffsetDateTime.class));
    }
    public ProductModels.Version version(String id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM product_versions WHERE id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->versionRow(rs),id); if(rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
    }
    @Transactional(readOnly=true) public List<ProductModels.Version> versions(String product) { product(product,false); return jdbc.query("SELECT * FROM product_versions WHERE product_id=? ORDER BY version_number",(rs,n)->versionRow(rs),product); }
    @Override public PublishedVersion requirePublished(String id) { return published(id,false); }
    @Override public PublishedVersion requireAvailableForNewUse(String id) { return published(id,true); }
    private PublishedVersion published(String id,boolean requireActive) {
        var rows=jdbc.query("SELECT v.id,v.product_id,p.name,p.model,v.version_number,v.status,v.parser_tested,v.revision,v.battery_low_comparator,v.battery_low_threshold,v.capabilities_json,p.state FROM product_versions v JOIN products p ON p.id=v.product_id WHERE v.id=?",(rs,n)->new Object[]{new PublishedVersion(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getLong(5),rs.getLong(8),rs.getString(9),rs.getBigDecimal(10),json.parse(rs.getString(11),com.fasterxml.jackson.databind.JsonNode.class).path("battery").asText("PENDING")),rs.getString(6),rs.getBoolean(7),rs.getString(12)},id);
        if(rows.isEmpty()) throw ApiError.missing(); var row=rows.getFirst();
        if(!row[1].equals("PUBLISHED") || !Boolean.TRUE.equals(row[2])) throw ApiError.conflict("VERSION_NOT_PUBLISHED","只能选择已发布的具体产品版本");
        if(requireActive && !"ACTIVE".equals(row[3])) throw ApiError.conflict("PRODUCT_ARCHIVED","产品已归档，不能用于新的注册、绑定或迁移目标");
        return (PublishedVersion)row[0];
    }
}
