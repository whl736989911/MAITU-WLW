package com.fireplatform.device;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read the old platform's terminal workbook. Hidden dropdown-list sheets are not asset data. */
@Service
public class LegacyTerminalSpreadsheetPreview {
    private static final int MAX_ROWS = 200;
    private static final List<String> HEADERS = List.of("所属系统", "应用", "平台", "产品", "设备名称", "所属位置", "设备编号",
        "入网模式", "imei", "频点", "AppKey", "AppEUI", "NwkSKey", "AppSKey", "DevAddr", "安装人", "安装人电话", "安装位置", "GPS坐标");
    record Place(String id, String name, String parent) { }
    private record ProductReference(String name, String platform) { }
    private record Terminal(int line, ProductReference product, String name, String place, String serial, String address) { }
    public record Preview(List<VendorDeviceBulkInputs.Row> rows) { }

    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final LegacyRealtimeDeviceService legacy;

    public LegacyTerminalSpreadsheetPreview(JdbcTemplate jdbc, DeviceAccessScope scopes, LegacyRealtimeDeviceService legacy) {
        this.jdbc = jdbc;
        this.scopes = scopes;
        this.legacy = legacy;
    }

    @Transactional(readOnly=true)
    public Preview preview(String actor, String tenant, VendorDeviceBulkInputs.LegacyTerminalSpreadsheet input) {
        scopes.requireAll(actor, tenant);
        var terminals = read(VendorDeviceSpreadsheetService.decode(input.contentBase64()));
        var places = readPlaces(jdbc, tenant);
        var rows = new ArrayList<VendorDeviceBulkInputs.Row>(terminals.size());
        var seen = new HashMap<String, Integer>();
        var profiles = new HashMap<ProductReference, String>();
        var placeIds = new HashMap<String, String>();
        for (var terminal : terminals) {
            int line = terminal.line();
            Integer prior = seen.putIfAbsent(terminal.serial(),line);
            if (prior != null) throw bad(line,"设备编号","与第"+prior+"行重复");
            String profile;
            try { profile = profiles.computeIfAbsent(terminal.product(), ref -> legacy.terminalProfile(ref.name(),ref.platform(),input.legacyProfileId())); }
            catch (ApiError e) { throw field(line,"产品",e); }
            String place = placeIds.computeIfAbsent(terminal.place(), name -> resolvePlace(places,name,line));
            try { legacy.requireNewTerminalIdentity(terminal.serial()); }
            catch (ApiError e) { throw field(line,"设备编号",e); }
            rows.add(new VendorDeviceBulkInputs.Row(terminal.name(),terminal.serial(),place,terminal.address(),profile,null,line));
        }
        return new Preview(rows);
    }

    private static List<Terminal> read(byte[] bytes) {
        if (FileMagic.valueOf(bytes) != FileMagic.OLE2) throw ApiError.bad("请选择原平台 .xls 文件，不要修改文件后缀");
        try (var fs = new POIFSFileSystem(new ByteArrayInputStream(bytes))) {
            if (fs.getRoot().hasEntry("_VBA_PROJECT_CUR")) throw ApiError.bad("不允许含宏的表格");
            try (var book = new HSSFWorkbook(fs)) {
                var sheet = book.getSheet("终端设备");
                if (sheet == null) throw ApiError.bad("缺少「终端设备」工作表");
                var header = sheet.getRow(0);
                if (header == null || header.getLastCellNum() != HEADERS.size()) throw ApiError.bad("「终端设备」表头与原平台模板不一致");
                for (int col=0;col<HEADERS.size();col++) {
                    if (!HEADERS.get(col).equals(text(header,col,1))) throw bad(1,HEADERS.get(col),"表头与原平台模板不一致");
                }
                if (sheet.getLastRowNum() > MAX_ROWS) throw ApiError.bad("单次最多导入200行");
                var format = new DataFormatter(Locale.ROOT);
                var rows = new ArrayList<Terminal>();
                for (int index=1;index<=sheet.getLastRowNum();index++) {
                    var row = sheet.getRow(index);
                    if (row==null) continue;
                    int line = index+1;
                    boolean populated = false;
                    for (var cell : row) {
                        int column = cell.getColumnIndex();
                        if (cell.getCellType()==CellType.FORMULA) throw bad(line,column<HEADERS.size()?HEADERS.get(column):"额外列","不允许公式");
                        if (cell.getCellType()==CellType.BLANK || (cell.getCellType()==CellType.STRING && cell.getStringCellValue().isBlank())) continue;
                        if (column>=HEADERS.size()) throw bad(line,"额外列","字段超出原模板范围");
                        populated = true;
                    }
                    if (!populated) continue;
                    String platform = text(row,2,line);
                    if (platform.length()>200) throw bad(line,HEADERS.get(2),"最多200字符");
                    var product = new ProductReference(required(text(row,3,line),line,3,200),platform);
                    String name = required(text(row,4,line),line,4,200);
                    String place = required(text(row,5,line),line,5,500);
                    String serial = text(row,6,line);
                    if (!serial.matches("[\\x21-\\x7e]{1,64}")) throw bad(line,HEADERS.get(6),"须为1–64位非空 ASCII 设备标识");
                    String network = text(row,7,line);
                    if (!network.isEmpty() && !List.of("NB","CAT1","LoRa-OTAA","LoRa-ABP").contains(network))
                        throw bad(line,HEADERS.get(7),"未识别入网模式，不自动当作 NB");
                    String imei = text(row,8,line);
                    if (!imei.isEmpty() && !imei.matches("[0-9]{15}")) throw bad(line,"imei","须为15位数字文本");
                    if ("NB".equals(network) && imei.isEmpty()) throw bad(line,"imei","NB 模板须填写机身 IMEI");
                    if (!imei.isEmpty() && !imei.equals(serial))
                        throw bad(line,"imei","与设备编号不同，无法确认上报用哪个标识；请核对设备身份后单独登记");
                    String devAddr = text(row,14,line);
                    if ("LoRa-ABP".equals(network) && !devAddr.isEmpty() && !devAddr.matches("[0-9A-Fa-f]{8}"))
                        throw bad(line,"DevAddr","须为8位十六进制文本");
                    String address = format.formatCellValue(row.getCell(17)).strip();
                    if (address.length()>500) throw bad(line,HEADERS.get(17),"最多500字符");
                    // Keys, GPS, installer and old routing fields are not returned or written by local registration.
                    rows.add(new Terminal(line,product,name,place,serial,address.isEmpty()?null:address));
                }
                if (rows.isEmpty()) throw ApiError.bad("「终端设备」工作表没有设备数据行");
                return rows;
            }
        } catch (ApiError rejected) { throw rejected; }
        catch (IOException | RuntimeException invalid) { throw ApiError.bad("旧平台 XLS 无法解析，请重新导出原模板"); }
    }

    private static String text(Row row,int column,int line) {
        var cell = row.getCell(column);
        if (cell==null || cell.getCellType()==CellType.BLANK) return "";
        if (cell.getCellType()!=CellType.STRING) throw bad(line,HEADERS.get(column),"必须是文本单元格；请按原值填写，避免丢失前导零或精度");
        return cell.getStringCellValue().strip();
    }

    private static String required(String value,int line,int column,int max) {
        if (value.isEmpty()||value.length()>max) throw bad(line,HEADERS.get(column),"必填且最多"+max+"字符");
        return value;
    }

    static Map<String,Place> readPlaces(JdbcTemplate jdbc,String tenant) {
        var places = new HashMap<String,Place>();
        jdbc.query("SELECT id,name,parent_place_id FROM places WHERE tenant_id=? AND archived_at IS NULL", rs -> {
            var place = new Place(rs.getString(1),rs.getString(2),rs.getString(3));
            places.put(place.id(),place);
        },tenant);
        return places;
    }

    private static String resolvePlace(Map<String,Place> places,String reference,int line) {
        var matches = new ArrayList<Place>();
        for (var place : places.values()) {
            if (place.name().equals(reference)|| (reference.contains(" / ") && reference.equals(path(places,place)))) matches.add(place);
        }
        if (matches.size()==1) return matches.getFirst().id();
        if (matches.isEmpty()) throw bad(line,"所属位置","未在目标单位找到同名位置或完整路径");
        var paths = matches.stream().map(p -> path(places,p)).distinct().sorted().limit(5).toList();
        throw bad(line,"所属位置","当前单位有多个匹配位置，请填完整路径："+String.join("；",paths));
    }

    static String path(Map<String,Place> places,Place place) {
        var names = new ArrayDeque<String>();
        var seen = new HashSet<String>();
        for (Place node=place;node!=null && seen.add(node.id());node=places.get(node.parent())) names.addFirst(node.name());
        return String.join(" / ",names);
    }

    private static ApiError bad(int line,String column,String message) { return ApiError.bad("第"+line+"行「"+column+"」："+message); }
    private static ApiError field(int line,String column,ApiError error) {
        return new ApiError(error.status,error.code,"第"+line+"行「"+column+"」："+error.getMessage());
    }
}
