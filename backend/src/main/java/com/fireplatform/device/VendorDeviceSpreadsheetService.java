package com.fireplatform.device;

import com.fireplatform.shared.ApiError;
import com.fireplatform.shared.DeviceAccessScope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Vendor Excel templates and read-only normalization into the existing bulk-import grid. */
@Service
public class VendorDeviceSpreadsheetService {
    public record Template(String filename, String media_type, String content_base64, String content_sha256) { }
    public record Grid(List<List<String>> rows, Map<String,String> modelLabels, ImportUnit unit) { }
    public record ImportUnit(String id,String name) { }

    private static final int MAX_BYTES = 1_000_000, MAX_ROWS = 200, MAX_COLUMNS = 16;
    private static final String XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<String> LEGACY_HEADERS = List.of("设备名称", "设备标识", "位置ID", "完整位置路径", "地址", "旧型号配置ID", "上报标识");
    private static final List<String> GUIDED_HEADERS = List.of("设备名称", "设备标识", "归属单位", "安装位置", "设备型号", "安装地址", "上报标识");
    private static final List<String> GUIDED_SETUP_HEADERS = List.of("设备名称", "设备标识", "安装位置", "设备型号", "安装地址", "上报标识");
    private static final String ENTRY_SHEET = "设备", OPTIONS_SHEET = "下拉选项", SETUP_SHEET = "归属设置";
    private static final List<String> HIKVISION_HEADERS = List.of("设备名称", "设备标识", "位置ID", "完整位置路径", "地址");
    private static final List<String> TEXT_COLUMNS = List.of("设备标识", "位置ID", "旧型号配置ID", "上报标识");
    private static final Map<String, List<String>> HEADERS = Map.of(
        "LEGACY_REALTIME", LEGACY_HEADERS, "HIKVISION_FIRECLOUD", HIKVISION_HEADERS);
    private record ChoicePair(Map<String,String> places, Map<String,String> models) { }
    private record UnitOptions(ImportUnit unit, Map<String,LegacyTerminalSpreadsheetPreview.Place> places) { }
    private record WorkbookOptions(List<UnitOptions> units,Map<String,String> models) { }
    private final JdbcTemplate jdbc;
    private final DeviceAccessScope scopes;
    private final LegacyRealtimeDeviceService legacy;

    public VendorDeviceSpreadsheetService(JdbcTemplate jdbc,DeviceAccessScope scopes,LegacyRealtimeDeviceService legacy) {
        this.jdbc=jdbc;this.scopes=scopes;this.legacy=legacy;
    }

    public Template template(String source) {
        byte[] bytes = xlsx(headers(source),null);
        String filename = "LEGACY_REALTIME".equals(source) ? "旧烟感批量新建模板.xlsx" : "海康已有设备关联模板.xlsx";
        return templateFile(filename,bytes);
    }

    Template legacyTemplate(String actor) {
        var units = availableUnits(actor);
        var models = modelChoices();
        if (units.isEmpty()) throw ApiError.bad("没有可批量导入的单位或可唯一识别的位置");
        if (models.values().stream().allMatch(String::isEmpty))
            throw ApiError.bad("没有可唯一识别的旧烟感型号；请先维护对应解析定义");
        return templateFile("旧烟感批量新增（下拉模板）.xlsx",xlsx(GUIDED_HEADERS,new WorkbookOptions(units,models)));
    }

    private static Template templateFile(String filename,byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw ApiError.bad("模板超过1MB，请使用单台新增");
        return new Template(filename,XLSX_MEDIA_TYPE,Base64.getEncoder().encodeToString(bytes),sha256(bytes));
    }

    public Grid preview(String source,String encoded,String actor) {
        return read(decode(encoded),headers(source),"LEGACY_REALTIME".equals(source),actor);
    }

    private List<UnitOptions> availableUnits(String actor) {
        var scope=scopes.allPredicate(actor,"t.id");
        var args=new ArrayList<Object>();
        args.add(actor);args.addAll(scope.bindings());
        var units=new TreeMap<String,UnitOptions>(String.CASE_INSENSITIVE_ORDER);
        var byId=new HashMap<String,UnitOptions>();
        jdbc.query("""
            SELECT t.id,t.name,p.id,p.name,p.parent_place_id
            FROM tenants t JOIN user_tenant_grants g ON g.tenant_id=t.id AND g.user_id=?
            JOIN places p ON p.tenant_id=t.id AND p.archived_at IS NULL
            WHERE t.enabled=TRUE
            """+scope.and(),rs->{
            String id=rs.getString(1),name=rs.getString(2);
            var unit=byId.computeIfAbsent(id,key->new UnitOptions(new ImportUnit(key,name),new HashMap<>()));
            var place=new LegacyTerminalSpreadsheetPreview.Place(rs.getString(3),rs.getString(4),rs.getString(5));
            unit.places().put(place.id(),place);
        },args.toArray());
        for(var entry:byId.values()) {
            String name=entry.unit().name();
            if(units.containsKey(name)) units.put(name,null);
            else units.put(name,entry);
        }
        return units.values().stream().filter(unit->unit!=null).toList();
    }

    private ChoicePair catalog(String actor,ImportUnit unit) {
        Long matches=jdbc.queryForObject("""
            SELECT COUNT(*) FROM tenants t JOIN user_tenant_grants g ON g.tenant_id=t.id
            WHERE t.id=? AND t.name=? AND t.enabled=TRUE AND g.user_id=?
            """,Long.class,unit.id(),unit.name(),actor);
        if(matches==null || matches!=1) throw ApiError.forbidden("表格选择的单位不可用或未授权，请重新下载模板");
        scopes.requireAll(actor,unit.id());
        return new ChoicePair(placeChoices(unit.name(),LegacyTerminalSpreadsheetPreview.readPlaces(jdbc,unit.id())),modelChoices());
    }

    private static Map<String,String> placeChoices(String unit,Map<String,LegacyTerminalSpreadsheetPreview.Place> byId) {
        var places=new TreeMap<String,String>();
        for (var place : byId.values())
            places.merge(unit+" · "+LegacyTerminalSpreadsheetPreview.path(byId,place),place.id(),(a,b)->"");
        return places;
    }

    private Map<String,String> modelChoices() {
        var models=new TreeMap<String,String>();
        for (var model : legacy.workbookModels()) models.merge(model.label(),model.id(),(a,b)->"");
        return models;
    }

    private static List<String> headers(String source) {
        List<String> headers = HEADERS.get(source == null ? "" : source);
        if (headers == null) throw ApiError.bad("来源类型无效");
        return headers;
    }

    static byte[] decode(String encoded) {
        byte[] bytes;
        try {
            String raw = encoded == null ? "" : encoded.trim();
            if (raw.length() > 4 * ((MAX_BYTES + 2) / 3)) throw ApiError.bad("表格文件超过1MB");
            if (raw.isEmpty() || raw.length() % 4 != 0) throw new IllegalArgumentException();
            bytes = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException invalid) {
            throw ApiError.bad("文件编码无效");
        }
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw ApiError.bad("表格文件须为1字节至1MB");
        return bytes;
    }

    private static byte[] xlsx(List<String> headers,WorkbookOptions choices) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(ENTRY_SHEET);
            CellStyle text = workbook.createCellStyle();
            text.setDataFormat(workbook.createDataFormat().getFormat("@"));
            Row header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                Cell cell = header.createCell(column, CellType.STRING);
                cell.setCellValue(headers.get(column));
                sheet.setDefaultColumnStyle(column, text);
                sheet.setColumnWidth(column, Math.min(255, headers.get(column).length() * 2 + 8) * 256);
            }
            sheet.createFreezePane(0,1);
            if (choices != null) addDropdowns(workbook,sheet,choices);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot build XLSX template", failure);
        }
    }

    private Grid read(byte[] bytes,List<String> expected,boolean legacySource,String actor) {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') throw ApiError.bad("XLSX文件无效");
        try (InputStream in = new ByteArrayInputStream(bytes); Workbook workbook = WorkbookFactory.create(in)) {
            if (!(workbook instanceof XSSFWorkbook xssf)) throw ApiError.bad("仅支持XLSX格式");
            if (xssf.getPackage().getParts().stream().anyMatch(part -> part.getPartName().getName().toLowerCase(Locale.ROOT).contains("vbaproject")))
                throw ApiError.bad("不允许含宏文件");
            if (workbook.getNumberOfSheets() == 0) throw ApiError.bad("缺少设备工作表");
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > MAX_ROWS) throw ApiError.bad("单次最多导入200行");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            List<String> header = readHeader(sheet,formatter);
            boolean rowUnits = legacySource && header.equals(GUIDED_HEADERS);
            boolean setupUnit = legacySource && header.equals(GUIDED_SETUP_HEADERS);
            boolean guided = rowUnits || setupUnit;
            ChoicePair choices = null, downloaded = null;
            ImportUnit unit = null;
            Sheet options = null;
            var unitIds = new HashMap<String,String>();
            if (guided) {
                options = workbook.getSheet(OPTIONS_SHEET);
                var setup = workbook.getSheet(SETUP_SHEET);
                if (!ENTRY_SHEET.equals(sheet.getSheetName()) || options == null ||
                    (setupUnit ? setup == null || workbook.getNumberOfSheets() != 3 : workbook.getNumberOfSheets() != 2))
                    throw ApiError.bad("工作表不符合模板，请重新下载");
                for(var row:options) addChoice(unitIds,text(row,0),text(row,1));
                if(setupUnit) {
                    unit = resolveUnit(unitIds,text(setup.getRow(1),1),"「归属设置」");
                    choices = catalog(actor,unit);
                    downloaded = downloadedChoices(options,unit.id());
                }
            } else if (!header.equals(expected) || workbook.getNumberOfSheets() != 1) {
                throw ApiError.bad(legacySource ? "表头或工作表不符合模板，请使用本页下载的模板"
                    : "海康 XLSX 仅允许一个工作表，表头应为：" + String.join(",",expected));
            }
            List<List<String>> rows = new ArrayList<>();
            rows.add(expected);
            var modelLabels = new HashMap<String,String>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row != null && row.getLastCellNum() > header.size()) throw ApiError.bad("第" + (index + 1) + "行列数超出模板");
                List<String> values = new ArrayList<>(header.size());
                for (int column = 0; column < header.size(); column++) {
                    Cell cell = row == null ? null : row.getCell(column);
                    if (cell != null && cell.getCellType() == CellType.FORMULA)
                        throw ApiError.bad("模板不允许公式，第" + (index + 1) + "行");
                    if (cell != null && TEXT_COLUMNS.contains(header.get(column)) && cell.getCellType() != CellType.STRING && cell.getCellType() != CellType.BLANK)
                        throw ApiError.bad("第" + (index + 1) + "行" + header.get(column) + "必须是文本单元格；请使用本页下载的模板重新填写");
                    String value = cell == null ? "" : formatter.formatCellValue(cell);
                    if (value.length() > 32767) throw ApiError.bad("单元格超过Excel长度限制，第" + (index + 1) + "行");
                    values.add(value);
                }
                // Empty rows retain their Excel line number in the existing normalized import grid.
                if (!guided) rows.add(values);
                else if (values.stream().allMatch(String::isBlank)) rows.add(Collections.nCopies(expected.size(),""));
                else {
                    String line = "第"+(index+1)+"行";
                    if (rowUnits) {
                        String unitName = values.remove(2);
                        if (unit == null) {
                            unit = resolveUnit(unitIds,unitName,line);
                            choices = catalog(actor,unit);
                            downloaded = downloadedChoices(options,unit.id());
                        } else if (!unit.name().equals(unitName)) {
                            resolveUnit(unitIds,unitName,line);
                            throw ApiError.bad(line+"「归属单位」与前面设备不同；请按单位拆分文件后分别导入");
                        }
                    }
                    rows.add(resolveChoices(values,index+1,choices,downloaded,modelLabels));
                }
            }
            if (rowUnits && unit == null) throw ApiError.bad("表格中没有设备数据行");
            return new Grid(rows,modelLabels,unit);
        } catch (ApiError rejected) {
            throw rejected;
        } catch (Exception failure) {
            throw ApiError.bad("XLSX文件无法解析，请使用本页下载的模板");
        }
    }

    private static List<String> readHeader(Sheet sheet,DataFormatter formatter) {
        Row row = sheet.getRow(0);
        if (row == null) throw ApiError.bad("缺少表头，请使用本页下载的模板");
        List<String> header = new ArrayList<>();
        for (int column = 0; column < MAX_COLUMNS; column++) {
            Cell cell = row.getCell(column);
            if (cell == null || cell.getCellType() == CellType.BLANK) break;
            if (cell.getCellType() == CellType.FORMULA) throw ApiError.bad("模板不允许公式");
            header.add(formatter.formatCellValue(cell).trim());
        }
        if (row.getLastCellNum() != header.size()) throw ApiError.bad("表头有空列或超出模板");
        return header;
    }

    private static void addDropdowns(Workbook workbook,Sheet sheet,WorkbookOptions choices) {
        Sheet options=workbook.createSheet(OPTIONS_SHEET);
        String prefix="'"+OPTIONS_SHEET+"'!";
        int unitRow=0,placeRow=0,modelRow=0;
        for(var unit:choices.units()) {
            int start=placeRow;
            for(var place:placeChoices(unit.unit().name(),unit.places()).entrySet()) {
                if(place.getValue().isEmpty()) continue;
                put(options,placeRow,5,place.getKey());put(options,placeRow,6,place.getValue());
                put(options,placeRow++,7,unit.unit().id());
            }
            if(placeRow==start) continue;
            String range="ImportPlaces"+(unitRow+1);
            named(workbook,range,prefix+"$F$"+(start+1)+":$F$"+placeRow);
            put(options,unitRow,0,unit.unit().name());put(options,unitRow,1,unit.unit().id());put(options,unitRow++,2,range);
        }
        if(unitRow==0) throw ApiError.bad("没有可批量导入的单位或可唯一识别的位置");
        for(var model:choices.models().entrySet()) {
            if(model.getValue().isEmpty()) continue;
            put(options,modelRow,3,model.getKey());put(options,modelRow++,4,model.getValue());
        }
        named(workbook,"ImportUnits",prefix+"$A$1:$A$"+unitRow);
        named(workbook,"ImportUnitRanges",prefix+"$A$1:$C$"+unitRow);
        validation(sheet,1,MAX_ROWS,2,"ImportUnits");
        named(workbook,"ImportNoPlaces",prefix+"$I$1");
        named(workbook,"ImportModels",prefix+"$D$1:$D$"+modelRow);
        String escapedUnit="SUBSTITUTE(SUBSTITUTE(SUBSTITUTE($C2,\"~\",\"~~\"),\"*\",\"~*\"),\"?\",\"~?\")";
        validation(sheet,1,MAX_ROWS,3,"INDIRECT(IFERROR(VLOOKUP("+escapedUnit+",ImportUnitRanges,3,FALSE),\"ImportNoPlaces\"))");
        validation(sheet,1,MAX_ROWS,4,"ImportModels");
        workbook.setSheetHidden(workbook.getSheetIndex(options),true);
        sheet.setColumnWidth(0,28*256);sheet.setColumnWidth(1,24*256);sheet.setColumnWidth(2,32*256);
        sheet.setColumnWidth(3,48*256);sheet.setColumnWidth(4,54*256);sheet.setColumnWidth(5,31*256);
    }

    private static void put(Sheet sheet,int index,int column,String text) {
        Row row=sheet.getRow(index);
        if(row==null) row=sheet.createRow(index);
        row.createCell(column).setCellValue(text);
    }

    private static void named(Workbook workbook,String name,String expression) {
        var range=workbook.createName();
        range.setNameName(name);range.setRefersToFormula(expression);
    }

    private static void validation(Sheet sheet,int first,int last,int column,String formula) {
        var helper=sheet.getDataValidationHelper();
        var validation=helper.createValidation(helper.createFormulaListConstraint(formula),
            new CellRangeAddressList(first,last,column,column));
        validation.setErrorStyle(DataValidation.ErrorStyle.STOP);
        validation.createErrorBox("请从下拉列表选择","请确认表内已选择正确的归属单位，或重新下载最新模板。");
        validation.setShowErrorBox(true);
        sheet.addValidationData(validation);
    }

    private static ChoicePair downloadedChoices(Sheet sheet,String tenant) {
        var places=new HashMap<String,String>();
        var models=new HashMap<String,String>();
        for (Row row:sheet) {
            if(tenant.equals(text(row,7))) addChoice(places,text(row,5),text(row,6));
            addChoice(models,text(row,3),text(row,4));
        }
        return new ChoicePair(places,models);
    }

    private static void addChoice(Map<String,String> selected,String label,String id) {
        if (!label.isEmpty() && !id.isEmpty()) selected.merge(label,id,(a,b)->"");
    }

    private static String text(Row row,int column) {
        Cell cell=row==null ? null : row.getCell(column);
        return cell!=null && cell.getCellType()==CellType.STRING ? cell.getStringCellValue() : "";
    }

    private static ImportUnit resolveUnit(Map<String,String> units,String name,String location) {
        if(name.isBlank()) throw ApiError.bad(location+"「归属单位」未选，请从下拉列表选择");
        String id=units.get(name);
        if(id==null || id.isEmpty()) throw ApiError.bad(location+"「归属单位」不在下拉选项，请重新下载模板");
        return new ImportUnit(id,name);
    }

    private static List<String> resolveChoices(List<String> row,int line,ChoicePair current,ChoicePair downloaded,Map<String,String> modelLabels) {
        String place=resolveChoice(row.get(2).strip(),line,"安装位置",current.places(),downloaded.places());
        String modelLabel=row.get(3).strip();
        String model=resolveChoice(modelLabel,line,"设备型号",current.models(),downloaded.models());
        modelLabels.put(model,modelLabel);
        return List.of(row.get(0),row.get(1),place,"",row.get(4),model,row.get(5));
    }

    private static String resolveChoice(String label,int line,String field,Map<String,String> current,Map<String,String> downloaded) {
        String id=current.get(label);
        if (id==null || id.isEmpty() || !id.equals(downloaded.get(label)))
            throw ApiError.bad("第"+line+"行「"+field+"」未选择、已变更或不唯一，请从最新模板的下拉选项中选择");
        return id;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }
}
