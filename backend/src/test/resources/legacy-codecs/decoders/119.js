var TTL_VERSION = "2.0";

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

//服务 （下行） 指令必须带IMEI
// 参数配置 指令码07 
var TTL_SERVICE_SET_IP_ADDRESS_PORT = 'thing.service.setIpAddressPort'; //设置IP地址&端口
var TTL_SERVICE_SET_HEARTBEAT = 'thing.service.setHeartbeat'; //设置正常工作时间间隔
var TTL_SERVICE_SET_EMERGENCY_TIME = 'thing.service.setEmergencyTime'; //设置紧急报警时间
var TTL_SERVICE_SET_GENERAL_ALARM_TIME = 'thing.service.setGeneralAlarmTime'; //设置一般报警时间 
var TTL_SERVICE_SET_CONCENTRATION_THRESHOLD = 'thing.service.setConcentrationThreshold'; //设置烟雾浓度阈值
var TTL_SERVICE_SET_TEMPERATURE_LIMIT = 'thing.service.setTemperatureLimit'; //温度值阈值上、下限
// 控制数据 指令码04
var TTL_SERVICE_CONTROL_EMERGENCY_TIME = 'thing.service.controlEmergencyTime'; //紧急报警静默时间
var TTL_SERVICE_CONTROL_GENERAL_ALARM_TIME = 'thing.service.controlGeneralAlarmTime'; //一般报警静默时间
var TTL_SERVICE_CONTROL_DEVICE_RESTART = 'thing.service.controlDeviceRestart'; //设备重启  1 有效
var TTL_SERVICE_CONTROL_RESUME_FACTORY = 'thing.service.controlResumeFactory'; //恢复出厂 1 有效
var TTL_SERVICE_CONTROL_BUZZER_SILENCE_TIME = 'thing.service.controlBuzzerSilenceTime'; //蜂鸣器消音
// 查询数据 指令码05
var TTL_SERVICE_GET_IP_ADDRESS_PORT = 'thing.service.getIpAddressPort'; //查询IP地址&端口
var TTL_SERVICE_GET_HEARTBEAT = 'thing.service.getHeartbeat'; //查询正常工作时隔
var TTL_SERVICE_GET_EMERGENCY_TIME = 'thing.service.getEmergencyTime'; //查询紧急报警时间
var TTL_SERVICE_GET_GENERAL_ALARM_TIME = 'thing.service.getGeneralAlarmTime'; //查询一般报警时间
var TTL_SERVICE_GET_CONCENTRATION_THRESHOLD = 'thing.service.getConcentrationThreshold'; //查询烟雾浓度阈值
var TTL_SERVICE_GET_TEMPERATURE_LIMIT = 'thing.service.getTemperatureLimit'; //查询温度值报警阈值上、下限
var TTL_SERVICE_GET_REPORT_COUNT = 'thing.service.getReportCount'; //查询上报计数
var TTL_SERVICE_GET_IMSI = 'thing.service.getIMSI'; // 查询IMSI
var TTL_SERVICE_GET_REPORT_FAILED_FRAME_COUNT = 'thing.service.getReportFailedFrameCount'; //查询上报失败帧计数

//消息体解析数据成key=>value
function parseData2KeyValue(uint8Array) {
    var data = uint8Array.subarray(12, -3);
    var map = {};
    var begin = 0;
    for (let index = 0; index < data.length; index++) {
        let key = data[begin].toString(16);
        if (key.length < 2)
            key = '0' + key;
        let keyLen = data[begin + 1];
        let end = begin + 2 + keyLen;
        let value = dec2Hex(data.subarray(begin + 2, end));
        map[key.toUpperCase()] = value;
        begin = end;
        if (begin >= data.length)
            break;
    }
    return map;
}
function dec2Hex(array) {
    var str = '';
    for (let index = 0; index < array.length; index++) {
        let element = array[index].toString(16);
        if (element.length < 2)
            element = "0" + element;
        str = str.concat(element);
    }
    return str;
}

//时间戳转日期时间格式
function timestampToTime(timestamp) {
    var date = new Date(timestamp * 1000);//时间戳为10位需*1000，时间戳为13位的话不需乘1000
    var Y = date.getFullYear() + '-';
    var M = (date.getMonth() + 1 < 10 ? '0' + (date.getMonth() + 1) : date.getMonth() + 1) + '-';
    var D = (date.getDate() < 10 ? '0' + date.getDate() : date.getDate()) + ' ';
    var h = (date.getHours() < 10 ? '0' + date.getHours() : date.getHours()) + ':';
    var m = (date.getMinutes() < 10 ? '0' + date.getMinutes() : date.getMinutes()) + ':';
    var s = (date.getSeconds() < 10 ? '0' + date.getSeconds() : date.getSeconds());
    return Y + M + D + h + m + s;
}

// 16进制转有符号的10进制整数
function hexTransform(value) {
    let two = parseInt(value, 16).toString(2);
    let bitNum=value.length*4;
    if (two.length < bitNum) {
        while (two.length < bitNum) {
        two = "0" + two;
        }
    }
    if (two.substring(0, 1) == "0") {
        two = parseInt(two, 2);
        return two;
    } else {
        let two_unsign = "";
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
//16进制转字符串
function hexCharCodeToStr(hexCharCodeStr) {
　　var trimedStr = hexCharCodeStr.trim();
　　var rawStr = 
　　trimedStr.substr(0,2).toLowerCase() === "0x"
　　? 
　　trimedStr.substr(2) 
　　: 
　　trimedStr;
　　var len = rawStr.length;
　　if(len % 2 !== 0) {
// 　　　　alert("Illegal Format ASCII Code!");
　　　　return "";
　　}
　　var curCharCode;
　　var resultStr = [];
　　for(var i = 0; i < len;i = i + 2) {
　　　　curCharCode = parseInt(rawStr.substr(i, 2), 16); // ASCII Code Value
　　　　resultStr.push(String.fromCharCode(curCharCode));
　　}
　　return resultStr.join("");
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
    var dataView = new DataView(uint8Array.buffer, 0);
    var map = parseData2KeyValue(uint8Array);
    params['frame_protocol_ver'] = parseInt(dataView.getUint8(1).toString(16));//协议版本
    // params['frame_protocol_time_re'] = timestampToTime(dataView.getUint32(2));//时间
    params['fcnt'] = dataView.getUint16(6);//帧计数
    if (map.hasOwnProperty("03") && map.hasOwnProperty("65")) { //参数 
        if (map['03'] === '01') {
            params['model'] = 'TBS-N101'   //型号
        }
        params['IMEI'] = map['65']; //设备标识码
        params['CMSV'] = hexCharCodeToStr(map['67']); //通信模组软件版本
        params['nb_module_manufacturer_number'] = map['68']; //NB模块厂商编号
        params['hardware_version'] = parseInt((('0x' + map['05'] & 0xf0) >> 4).toString(10)); //硬件版本
        params['software_version'] = parseInt(('0x' + map['05'] & 0x0f).toString(10)); //软件版本
        params['heartbeat'] = parseInt(map['06'], 16); //正常工作间隔
        params['emergency_alarm_interval'] = parseInt(map['08'], 16);  //紧急报警间隔
        params['general_alarm_interval'] = parseInt(map['09'], 16);  //一般报警间隔
        params['concentration_threshold'] = parseInt(map['14'], 16); //烟雾浓度阈值
        params['temperature_alarm_up'] = parseInt(map['0B'], 16); //温度报警值上限
        params['temperature_alarm_down'] = hexTransform(map['21']);  //温度报警值下限
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("24") || map.hasOwnProperty('02')) { //状态数据
    	
    	params['IMEI'] = map['65'] // 设备标识码IMEI
        params['rssi'] = hexTransform(map['69']) // NB信号质量
        // params['failedFrameCount'] = parseInt(map['17'], 16) // 发送失败帧计数
        params['smoke_concentration'] = parseInt(map['14'], 16) // 烟雾浓度值
        params['temperature_value'] = hexTransform(map['0B']) // 温度值
        params['battery'] = parseInt(map['24'], 16) // 电池电量
        params['rsrp'] = (hexTransform(map['6C']) / 10 ).toFixed(1) // RSRP 参考信号接收功率
        params['sinr'] = (hexTransform(map['6D']) / 10 ).toFixed(1) // SNR 信躁比
        params['pci'] = parseInt(map['6E'], 16) // PCI 物理小区标识
        params['ecl'] = parseInt(map['6F'], 16) // ECL NB模组网络质量分值
        params['ICCID'] = map['70'] // ICCID SIM卡ID
        params['cell_id'] = parseInt(map['71'], 16) // CellID 小区ID
        if (map.hasOwnProperty("19")) {
            params['device_self_check'] = parseInt(map['19'], 16) // 设备自检
        }
    	
        if (map['01'] === '01') {
            params['deviceState'] = '火灾报警' // 设备状态--火灾报警、心跳
            params['smoke_concentration'] = parseInt(map['14'], 16) // 烟雾浓度值
            params['temperature_value'] = hexTransform(map['0B']) // 温度值
            params['deviceStateCode'] = '1001001' // 设备状态Code码
            params['level'] = 1 // 事件级别
            jsonMap['method'] = TTL_EVENT_ALARMS;
        } else if (map['01'] === '02'){ // 设备状态（温度超限报警）
            params['temperature_value'] = hexTransform(map['0B']) // 温度值
            params['deviceState'] = '温度超限' // 设备状态--温度超限报警 -- 20221226 改为非故障状态
            // params['deviceStateCode'] = '1001002'
            // params['level'] = 1
            jsonMap['method'] = TTL_EVENT_OTHER;
        } else if (map['02'] === '02'){ // 设备状态（烟感电量低）
            params['battery'] = parseInt(map['24'], 16); //设备电量
            params['deviceState'] = '烟感低电量报警' // 设备状态--烟感电量低
            // params['deviceStateCode'] = '1002027'
            // params['level'] = 2
            jsonMap['method'] = TTL_EVENT_OTHER;
        } else if (map['02'] === '03'){ // 设备状态（防拆报警）
            params['deviceState'] = '防拆报警' // 设备状态--防拆报警
            // params['deviceStateCode'] = '1002003'
            // params['level'] = 2
            jsonMap['method'] = TTL_EVENT_OTHER;
        } else if (map['02'] === '07'){ // 设备状态（发射电路故障）
            params['deviceState'] = '发射电路故障' // 设备状态--发射电路故障
            // params['deviceStateCode'] = '1002005'
            // params['level'] = 3
            jsonMap['method'] = TTL_EVENT_OTHER;
        } else {
            params['deviceState'] = '心跳上报' // 设备状态--心跳
            jsonMap['method'] = TTL_EVENT_HEARTBEAT;
        }
    } else if (map['19'] === '01') {  //自检
        params['IMEI'] = map['65']; //设备标识码
        params['deviceState'] = '设备自检' // 设备状态
        params['device_self_check'] = parseInt(map['19'], 16) // 设备自检
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty('16')) { //上行包计数
        params['IMEI'] = map['65']; //设备标识码
        params['report_count'] = parseInt(map['16'].substring(4, 8), 16);//上报计数
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("6A") || map.hasOwnProperty("6B")) { // 下行应答 设置/查询
        params['ip_address'] = Str2Bytes(map['6A']).join("."); //IP地址
        params['port'] = parseInt(map['6B'], 16); //端口
        params['IMEI'] = map['65']; //设备标识码
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("06")) { // 下行应答
        params['IMEI'] = map['65']; //设备标识码
        params['heartbeat'] = parseInt(map['06'], 16); //正常工作间隔
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("08")) { // 下行应答
        params['IMEI'] = map['65'] // 设备标识码
        params['emergency_alarm_interval'] = parseInt(map['08'], 16);  //紧急报警间隔
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("09")) { // 下行应答
        params['IMEI'] = map['65'] // 设备标识码
        params['general_alarm_interval'] = parseInt(map['09'], 16);  //一般报警间隔
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("14")) { // 下行应答
        params['IMEI'] = map['65'] // 设备标识码
        params['concentration_threshold'] = parseInt(map['14'], 16) // 烟雾浓度阈值
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("0B") || map.hasOwnProperty("21")) { // 下行应答
        params['IMEI'] = map['65'] // 设备标识码
        params['temperature_alarm_up'] = parseInt(map['0B'], 16); //温度值报警值上限
        params['temperature_alarm_down'] = hexTransform(map['21']);  //温度报警值下限
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("66")) { // 下行应答
        params['IMEI'] = map['65'] // 设备标识码
        params['IMSI'] = map['66'];  //IMSI
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty('17')) { //下行查询
        params['IMEI'] = map['65'] // 设备标识码
        params['failed_frame_count'] = parseInt(map['17'], 16); //发送失败帧计数
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("0C") || map.hasOwnProperty("0D")) { // 下行应答 设备重启/恢复出厂 不解析
        params['IMEI'] = map['65'] // 设备标识码
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("20")) { // 下行应答
        params['IMEI'] = map['65'] // 设备标识码
        params['buzzer_silence_time'] = parseInt(map['20'], 16); //蜂鸣器消音
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (uint8Array[10] == 2) { // 注册
        params['IMEI'] = map['65']; //设备标识码
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("65")) { // 下行应答 设置/查询
        params['IMEI'] = map['65']; //设备标识码
        jsonMap['method'] = TTL_EVENT_PARAMS;
    }
    jsonMap['version'] = TTL_VERSION;
    jsonMap['params'] = [params];
    jsonArr.push(jsonMap)
    return JSON.stringify(jsonArr);
}

function protocolToRawData(json) {
    var method = json['method'];
    var params = json['params'];
    var payloadArray = [];

    var method = json['method'];
    var version = json['version'];
    var params = json['params'];
    var payloadArray = [];
    // 指令 类型&码
    var instructionType = '';
    var instructionCode = '';
    // 指令 - 查询
    if (method.indexOf('get') == 14) {
        instructionType = 'get'
        instructionCode = 0x05
    }
    // 指令 - 控制
    if (method.indexOf('control') == 14) {
        instructionType = 'control'
        instructionCode = 0x04
    }
    // 指令 - 配置
    if (method.indexOf('set') == 14) {
        instructionType = 'set'
        instructionCode = 0x07
    }
    // Tag Length Value 值
    var TLV_Data = {
        ipAddress: [0x6A, 04, ''],
        port: [0x6B, 02, ''],
        heartbeat: [0x06, 03, ''],
        emergencyTime: [0x08, 03, ''],
        generalAlarmTime: [0x09, 03, ''],
        concentrationThreshold: [0x14, 02, ''],
        upperTemperatureLimit: [0x0B, 01, ''],
        lowerTemperatureLimit: [0x21, 01, ''],
        deviceRestart: [0x0C, 01, 01],
        resumeFactory: [0x0D, 01, 01],
        buzzerSilenceTime: [0x20, 03, ''],
        reportCount: [0x16, '', ''],
        getIMSI: [0x66, '', ''],
        reportFailedFrameCount: [0x17, '', '']
    }
    // 消息体参数
    var paramsMethod = {}
    // 消息体参数 - 配置
    paramsMethod[TTL_SERVICE_SET_IP_ADDRESS_PORT] = ['ipAddress', 'port']
    paramsMethod[TTL_SERVICE_SET_HEARTBEAT] = ['heartbeat']
    paramsMethod[TTL_SERVICE_SET_EMERGENCY_TIME] = ['emergencyTime']
    paramsMethod[TTL_SERVICE_SET_GENERAL_ALARM_TIME] = ['generalAlarmTime']
    paramsMethod[TTL_SERVICE_SET_CONCENTRATION_THRESHOLD] = ['concentrationThreshold']
    paramsMethod[TTL_SERVICE_SET_TEMPERATURE_LIMIT] = ['upperTemperatureLimit', 'lowerTemperatureLimit']
    // 消息体参数 - 控制
    paramsMethod[TTL_SERVICE_CONTROL_EMERGENCY_TIME] = ['emergencyTime']
    paramsMethod[TTL_SERVICE_CONTROL_GENERAL_ALARM_TIME] = ['generalAlarmTime']
    paramsMethod[TTL_SERVICE_CONTROL_DEVICE_RESTART] = ['deviceRestart']
    paramsMethod[TTL_SERVICE_CONTROL_RESUME_FACTORY] = ['resumeFactory']
    paramsMethod[TTL_SERVICE_CONTROL_BUZZER_SILENCE_TIME] = ['buzzerSilenceTime']
    // 消息体参数 - 查询
    paramsMethod[TTL_SERVICE_GET_IP_ADDRESS_PORT] = ['ipAddress', 'port']
    paramsMethod[TTL_SERVICE_GET_HEARTBEAT] = ['heartbeat']
    paramsMethod[TTL_SERVICE_GET_EMERGENCY_TIME] = ['emergencyTime']
    paramsMethod[TTL_SERVICE_GET_GENERAL_ALARM_TIME] = ['generalAlarmTime']
    paramsMethod[TTL_SERVICE_GET_CONCENTRATION_THRESHOLD] = ['concentrationThreshold']
    paramsMethod[TTL_SERVICE_GET_TEMPERATURE_LIMIT] = ['upperTemperatureLimit', 'lowerTemperatureLimit']
    paramsMethod[TTL_SERVICE_GET_REPORT_COUNT] = ['reportCount']
    paramsMethod[TTL_SERVICE_GET_IMSI] = ['getIMSI']
    paramsMethod[TTL_SERVICE_GET_REPORT_FAILED_FRAME_COUNT] = ['reportFailedFrameCount']
    var msgBody = new Array();
    // 5.1、消息体&消息体长度 - 设备IMEI NB设备必加
    msgBody = msgBody.concat(0x65).concat(0x08)
    msgBody = msgBody.concat(Str2Bytes(PrefixInteger(params['IMEI'], 16)));
    // 1、包头 1Byte
    payloadArray = payloadArray.concat(0x7E);
    // 2、协议版本 1Byte
    payloadArray = payloadArray.concat(0x01);
    // 3、时间 4Byte
    var timestamp = Date.parse(new Date()) / 1000;
    payloadArray = payloadArray.concat(buf8hex(timestamp.toString(16)));
    // 4、帧号 2Byte
    payloadArray = payloadArray.concat(buffer_int16(0x0001));
    // 5.1、消息体&消息体长度
    var paramsList = paramsMethod[method]
    // console.log(paramsList)
    for (var i in paramsList) {
        var key = paramsList[i]
        var TLV_Item = TLV_Data[key]
        var paramsVal = params[key]
        if (TLV_Item) {
            var Tag = TLV_Item[0]
            var Length = TLV_Item[1]
            var Value = TLV_Item[2]
            msgBody = msgBody.concat(Tag);
            // 6.1、指令 - 配置
            if (instructionType == 'control' || instructionType == 'set') {
                msgBody = msgBody.concat(Length);
                if (Value) {
                    // 消息体 - 处理消息体
                    msgBody = msgBody.concat(Value);
                } else {
                    // 5.1、消息体 - 处理消息体长度
                    if (Length == 01) {
                        let newVal = parseInt(paramsVal)
                        if(newVal < 0){
                            newVal +=256;
                        }
                        msgBody = msgBody.concat(newVal)
                    } else {
                        var temp = new Array();
                        if (key == 'ipAddress') {
                            var ipList = paramsVal.split('.')
                            for (var i in ipList) {
                                temp = temp.concat(parseInt(ipList[i]))
                            }
                        } else {
                            if (typeof paramsVal == 'string') {
                                paramsVal = parseInt(paramsVal)
                            }
                            temp = Str2Bytes(PrefixInteger(paramsVal.toString(16), parseInt(Length) * 2))
                        }
                        msgBody = msgBody.concat(temp);
                    }
                }
            }
        }
    }
    // 5.2、消息体长度 - TLV长度
    payloadArray = payloadArray.concat(Str2Bytes(PrefixInteger((msgBody.length).toString(16), 4)));
    // 6、指令 1Byte  0x05查询 0x07配置 
    payloadArray = payloadArray.concat(instructionCode);
    // 7、加密 1Byte
    payloadArray = payloadArray.concat(0x00);
    // 8、消息体 NByte
    payloadArray = payloadArray.concat(msgBody);
    // 9、CRC 2Byte
    payloadArray = payloadArray.concat(buffer_int16(0x0000));
    // 10、包尾 1Byte
    payloadArray = payloadArray.concat(0x7E);
    return payloadArray;
}

// 字节 --> 2 byte
function Str2Bytes(str) {
	var pos = 0;
    var len = str.length
    if (len % 2 != 0) {
		return null; 
	}
	len /= 2;
    var hexA = new Array()
    for (var i=0; i<len; i++) {
        var s = str.substr(pos, 2)
        var v = parseInt(s, 16)
        hexA.push(v)
        pos += 2
    }
    return hexA;
}

// 处理IMEI 10进制转16进制
function buf8hex(buffer) {
    let bufferStr = buffer.toString()
    let start = 0
    let end = 2
    let index = 0
    let list = []
    // let [start, end, index, list] = [0, 2, 0, []]
    for (let i in bufferStr) {
        start = index
        end = index + 2
        let item = bufferStr.slice(start, end)
        if (start == index && i > 0 && i < (bufferStr.length + 1) / 2) {
            // push
            list.push(parseInt(item,16))
        }
        index = i * 2
    }
    return list
}

// 消息体长度转换为 4位
function PrefixInteger(num, length) {
    return (Array(length).join('0') + num).slice(-length);
}

//以下是部分辅助函数
function buffer_uint8(value) {
    var uint8Array = new Uint8Array(1);
    var dv = new DataView(uint8Array.buffer, 0);
    dv.setUint8(0, value);
    return [].slice.call(uint8Array);
}
function buffer_int16(value) {
    var uint8Array = new Uint8Array(2);
    var dv = new DataView(uint8Array.buffer, 0);
    dv.setInt16(0, value);
    return [].slice.call(uint8Array);
}
function buffer_int32(value) {
    var uint8Array = new Uint8Array(4);
    var dv = new DataView(uint8Array.buffer, 0);
    dv.setInt32(0, value);
    return [].slice.call(uint8Array);
}
function buffer_float32(value) {
    var uint8Array = new Uint8Array(4);
    var dv = new DataView(uint8Array.buffer, 0);
    dv.setFloat32(0, value);
    return [].slice.call(uint8Array);
}
