var TTL_VERSION = "1.0";
//事件 （上行）
//告警事件
var TTL_EVENT_ALARMS = 'thing.event.alarms' // 告警
//故障事件
var TTL_EVENT_FAULTS = 'thing.event.faults' // 故障
//参数事件
var TTL_EVENT_PARAMS = 'thing.event.params' // 参数
//心跳事件
var TTL_EVENT_HEARTBEAT = 'thing.event.heartbeat' // 心跳
//其它类型
var TTL_EVENT_OTHER = 'thing.event.other' // 其它

// 服务(下行控制帧)
var TTL_SERVICE_STOP_EMERGENCY_ALARM = 'thing.service.stopEmergencyAlarm'; // 停止紧急报警
var TTL_SERVICE_STOP_GENERAL_ALARM = 'thing.service.stopGeneralAlarm'; // 停止非紧急报警

// 服务(下行参数命令帧)
var TTL_SERVICE_SET_NUAI = 'thing.service.setNUAI'; // 设置一般报警上报间隔
var TTL_SERVICE_SET_SM = 'thing.service.setSM'; // 设置报警型号
var TTL_SERVICE_SET_UAI = 'thing.service.setUAI'; // 设置紧急报警上报间隔
var TTL_SERVICE_SET_HBI = 'thing.service.setHBI'; // 设置工作状态上报间隔
var TTL_SERVICE_SET_MDI = 'thing.service.setMDI'; // 设置无线底座检测间隔
var TTL_SERVICE_SET_RESTORE = 'thing.service.setRestore'; // 恢复终端出厂设置并重启
var TTL_SERVICE_SET_RESET = 'thing.service.setReset'; // 复位并重启终端
var TTL_SERVICE_SET_TEMPTHRH = 'thing.service.setTempThrH'; // 温度超限阀值上限设置
var TTL_SERVICE_SET_TEMPTHRL = 'thing.service.setTempThrL'; // 温度超限阀值下限设置

// 帧类型
var FRAME_TYPE_PARAM = 0xB3; // 上行参数帧 
var FRAME_TYPE_ALARM_STATE = 0xB1; //上行状态帧 报警
var FRAME_TYPE_WORK_STATE = 0xB2; // 上行状态帧 心跳

//包计数帧转换 包计数共32个bit 从低位到高位0-31 表示为低位在前，高位在后（取反） 如：69010000 -> 00 00 01 69 -> 16进制转为10进制
function GetPacketCountString(str, offset) {
    var val = ''
    if (str.length < 1) {
        val = null;
    } else {
        for (var i = 4; i > 0; i--) {
            if (str[offset + i - 1] & 0xf0) {
                val += str[offset + i - 1].toString(16);
            } else {
                val += '0' + str[offset + i - 1].toString(16);
            }
        }
    }
    return parseInt(val, 16);
}

// 16进制转有符号的10进制整数
function hexTransform(value) {
    var two = parseInt(value).toString(2);
    var bitNum=value.length*4;
    if (two.length < bitNum) {
        while (two.length < bitNum) {
        two = "0" + two;
        }
    }
    if (two.substring(0, 1) == "0") {
        two = parseInt(two, 2);
        return two;
    } else {
        var two_unsign = "";
        two = parseInt(two, 2) - 1;
        two = two.toString(2);
        two_unsign = two.substring(1, bitNum);
        two_unsign = two_unsign.replace(/0/g, "z");
        two_unsign = two_unsign.replace(/1/g, "0");
        two_unsign = two_unsign.replace(/z/g, "1");
        two = parseInt(-two_unsign, 2);
        return two;
    }
}

//解析设备上传的原始数据
function rawDataToProtocol(bytes) {
    var uint8Array = new Uint8Array(bytes.length);
    for (var i = 0; i < bytes.length; i++) {
        uint8Array[i] = bytes[i] & 0xff;
    }
    var params = {};
    var jsonMap = {};
    var jsonArr = [];
    params['fcnt'] = (uint8Array[1] & 0xf0) >> 4; // 帧计数
    var fhead = uint8Array[0];
    if (fhead == FRAME_TYPE_PARAM) { //参数
        params['terminal_type'] = getMoteType(uint8Array[1]); //终端类型
        params['model'] = getModel(uint8Array[2]); //型号
        params['hardware_version'] = ((uint8Array[3] & 0xf0) >> 4).toString(10); //硬件版本
        params['software_version'] = (uint8Array[3] & 0x0f).toString(10); //软件版本
        params['heartbeat'] = timeInterval((uint8Array[4] & 0xf0) >> 4, 'HeartbeatInterval'); // 工作状态定时上报间隔
        params['wireless_base_detect_interval'] = timeInterval(uint8Array[4] & 0x0f, 'MDetectInterval'); // 无线底座检测间隔
        params['emergency_alarm_interval'] = uint8Array[5] * 30; // 报警上报间隔,范围1~10 ，单位为秒，步长为 30 秒，默认值为1 30 秒）。
        params['temperature_alarm_down'] = unsignValtoTemp(uint8Array[6]);//温度超限阀值下限
        params['temperature_alarm_up'] = uint8Array[7];//温度超限阀值上限
        params['general_alarm_interval'] = timeInterval(uint8Array[8], 'NotUrgentAlarmInterval'); //一般报警上报间隔设置
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (fhead == FRAME_TYPE_ALARM_STATE) { //报警和故障
        if (uint8Array[1] & 0x08 ) {
            params['deviceState'] = '火灾报警' // FRA 设备状态--火灾报警
            params['deviceStateCode'] = '1001001'
            params['level'] = 1
            jsonMap['method'] = TTL_EVENT_ALARMS;
        } else if (uint8Array[1] & 0x04) {
            params['deviceState'] = '独立式烟感故障报警' // SFA 设备状态--独立式烟感故障报警
            params['deviceStateCode'] = '1002044'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (uint8Array[1] & 0x02) {
            params['deviceState'] = '独立式烟感低电量报警' // SLBA 设备状态--独立式烟感低电量报警
            params['deviceStateCode'] = '1002045'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (uint8Array[1] & 0x01) {
            params['deviceState'] = '无线底座温度超限' // TPA 设备状态--无线底座温度超限报警 -- 解析为正常消息
            jsonMap['method'] = TTL_EVENT_OTHER;
            // params['deviceStateCode'] = '1002012'
            // params['level'] = 2
            // jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (uint8Array[2] & 0x10) {
            params['deviceState'] = '无线底座防拆报警' // SOA 设备状态--无线底座防拆报警
            params['deviceStateCode'] = '1002006'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (uint8Array[2] & 0x08){
            params['deviceState'] = '独立式烟感与无线底座失联报警' // SLFA 设备状态--独立式烟感与无线底座失联报警
            params['deviceStateCode'] = '1002007'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (uint8Array[2] & 0x04){
        	 params['deviceState'] = '无线底座温度传感器故障' // TSFA 设备状态--无线底座温度传感器故障报警 -- 解析为正常消息
             jsonMap['method'] = TTL_EVENT_OTHER;
        } else if (uint8Array[2] & 0x02) {
            params['deviceState'] = '无线底座低电压报警' // MLBA 设备状态--无线底座低电压报警
            params['deviceStateCode'] = '1002009'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (uint8Array[2] & 0x01){
        	 params['deviceState'] = '无线底座其他故障报警' // 设备状态--无线底座其他故障报警
             params['deviceStateCode'] = '1002010'
             params['level'] = 2
             jsonMap['method'] = TTL_EVENT_FAULTS;
        }
    } else if (fhead == FRAME_TYPE_WORK_STATE) { //心跳
        var devSelfCheck = uint8Array[2] & 0x80;
        if (devSelfCheck){
            params['deviceState'] = '设备手动自检' // 设备状态--设备自检
            params['device_self_check'] = devSelfCheck; // 设备自检
            jsonMap['method'] = TTL_EVENT_OTHER;
       } else {
            params['temperature_value'] = uint8Array[3]  //供电状态指示 0：表示电池供电，1：表示外部电源供电。
            params['deviceState'] = '心跳上报'; // 心跳上报
            jsonMap['method'] = TTL_EVENT_HEARTBEAT;
       }
    } else { // 其它 
        // params['TotalPacketCount'] = GetPacketCountString(uint8Array,1)//包总计数
        // params['AlarmFrameCount'] = GetPacketCountString(uint8Array,5) //报警帧总计数
        // params['HeartFrameCount'] = GetPacketCountString(uint8Array,9) //心跳帧总计数
        jsonMap['method'] = TTL_EVENT_OTHER;
    }
    jsonMap['version'] = TTL_VERSION;
    jsonMap['params'] = [params];
    jsonArr.push(jsonMap)
    return JSON.stringify(jsonArr);
}

//下行命令
function protocolToRawData(json) {
    var method = json['method'];
    var params = json['params'];
    var payloadArray = [];
    if (method == TTL_SERVICE_STOP_EMERGENCY_ALARM) { // 停止紧急报警
        payloadArray = payloadArray.concat(0xb4); // 帧类型 b4 下行控制帧  --第0个byte（字节） -->> 1 byte => 8 bit
        payloadArray = payloadArray.concat((params['silentTime'] << 4) & 0xf0); // (data[0]&0x0f)<<4 低四位  (data[0]&0xf0)>>4  高四位 --第1个byte（字节）
        payloadArray = payloadArray.concat(0x00); // --第2个byte（字节）
        payloadArray = payloadArray.concat(0x00); // --第3个byte（字节）
        payloadArray = payloadArray.concat(0x02); // --第4个byte（字节）
        payloadArray = payloadArray.concat(0x00); // --第5个byte（字节）
    } else if (method == TTL_SERVICE_STOP_GENERAL_ALARM) { // 停止一般报警
        payloadArray = payloadArray.concat(0xb4);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x01);
        payloadArray = payloadArray.concat(0x00); // --第5个byte（字节）
    } else if (method == TTL_SERVICE_SET_NUAI) { // 设置一般报警上报间隔
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x80);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(params['setNUAI']);
    } else if (method == TTL_SERVICE_SET_SM) { // 设置报警型号
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x40);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat((params['setSM'] >> 4) & 0x0f);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_UAI) { // 设置紧急报警上报间隔
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x20);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(params['setUAI']);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_HBI) { // 设置工作状态上报间隔
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x10);
        payloadArray = payloadArray.concat(params['setHBI'] & 0x0f);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_MDI) { // 设置无线底座检测间隔
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x08);
        payloadArray = payloadArray.concat((params['setMDI'] << 4) & 0xf0);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_RESTORE) { // 恢复终端出厂设置并重启
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x02);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_RESET) { // 复位并重启终端
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x01);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_TEMPTHRH) { // 温度超限阀值上限设置
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x04);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(params['setTempThrH']);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } else if (method == TTL_SERVICE_SET_TEMPTHRL) { // 温度超限阀值下限设置
        payloadArray = payloadArray.concat(0xb5);
        payloadArray = payloadArray.concat(0x04);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(tempValtoUnsign(parseInt(params['setTempThrL'])));
        payloadArray = payloadArray.concat(0x00);
        payloadArray = payloadArray.concat(0x00);
    } 
   
    payloadArray = payloadArray.concat(0xe1); // 帧尾 
    return payloadArray;
}

//时间间隔转换
function timeInterval(val, paramsInterval) {
    var newVal = val.toString(16)
    if (paramsInterval === 'HeartbeatInterval') { // 工作状态定时上报间隔
        var HBIArray = [30, 3600, 10800, 21600, 43200, 86400, 0]
        newVal = HBIArray[val]
    } else if (paramsInterval === 'MDetectInterval') { // 无线底座检测间隔
        var MDIArray = [15, 30, 60, 300, 600, 1800, 3600, 0]
        newVal = MDIArray[val]
    } else if (paramsInterval === 'UrgentAlarmInterval') { // 紧急报警上报间隔  n*30s 30~300s
        newVal = val * 30
    } else if (paramsInterval === 'NotUrgentAlarmInterval') { // 一般报警上报间隔设置
        var NUAIArray = [30, 1800, 3600, 7200, 21600, 43200, 86400, 0]
        newVal = NUAIArray[val]
    }
    return newVal
}

//终端类型
function getMoteType(MoteType) {
    if (MoteType == 0x1C) {
        return 'TBS-110'
    } else {
        return '-'
    }
}

//终端型号
function getModel(Model) {
    var arr = ['-', 'TBS-100', 'TBS-A103', 'TBS-110-A', 'TBS-110-B', 'TBS-110-C', 'TBS-110-D']
    var newVal = arr[Model]
    return newVal
}

//温度阈值转换
function tempValtoUnsign(num){
	if(num < 0){
		num +=256;
	}
	return num;
}

//温度阈值转换
function unsignValtoTemp(num){
	if(num > 0 && num < 256){
		num -=256;
	}
	return num;
}

// 字节数组转整数
function byteArray2Int(bytes) {
    var value = 0;
    for (var i = 0; i < bytes.length; i++) {
        var shift = (bytes.length - 1 - i) * 8;
        value += (bytes[i] & 0x000000FF) << shift;
    }
    return value;
}

