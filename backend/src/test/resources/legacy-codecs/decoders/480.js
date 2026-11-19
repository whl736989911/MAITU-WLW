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
var TTL_SERVICE_SET_HEARTBEAT = 'thing.service.setHeartbeat'; //设置正常工作时间间隔
var TTL_SERVICE_SET_EMERGENCY_TIME = 'thing.service.setEmergencyTime'; //设置紧急报警时间
var TTL_SERVICE_SET_GENERAL_ALARM_TIME = 'thing.service.setGeneralAlarmTime'; //设置一般报警时间
var TTL_SERVICE_SET_TEMPERATURE_LIMIT = 'thing.service.setTemperatureLimit'; //温度值阈值上、下限
// 控制数据 指令码04
var TTL_SERVICE_CONTROL_DEVICE_RESTART = 'thing.service.controlDeviceRestart'; //设备重启  1 有效
var TTL_SERVICE_CONTROL_RESUME_FACTORY = 'thing.service.controlResumeFactory'; //恢复出厂 1 有效
var TTL_SERVICE_CONTROL_BUZZER_SILENCE_TIME = 'thing.service.controlBuzzerSilenceTime'; //蜂鸣器消音 86400s-30s
// 查询数据 指令码05
var TTL_SERVICE_GET_HEARTBEAT = 'thing.service.getHeartbeat'; //查询正常工作时隔
var TTL_SERVICE_GET_EMERGENCY_TIME = 'thing.service.getEmergencyTime'; //查询紧急报警时间
var TTL_SERVICE_GET_GENERAL_ALARM_TIME = 'thing.service.getGeneralAlarmTime'; //查询一般报警时间
var TTL_SERVICE_GET_CONCENTRATION_THRESHOLD = 'thing.service.getConcentrationThreshold'; //查询烟雾浓度阈值
var TTL_SERVICE_GET_TEMPERATURE_LIMIT = 'thing.service.getTemperatureLimit'; //查询温度值报警阈值上、下限
var TTL_SERVICE_GET_REPORT_COUNT = 'thing.service.getReportCount'; //查询上报计数
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


function mergeArray(arr) {
	// 数组 奇数、偶数两两合并
	var newArr = []
	for (var i in arr) {
		var str = ""
		if ((i&1) === 0) {
			// 偶数
			str = arr[parseInt(i)]+arr[parseInt(i)+1]
			newArr.push(str)
		}
	}
	return newArr
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
    params['fcnt'] = dataView.getUint16(6);//帧计数
    var checkParams = {}
    var checkObj = {
        deviceState: "设备自检",
        device_self_check: parseInt(map['19'], 16) // 设备自检
    }
    checkParams['method'] = TTL_EVENT_OTHER
    checkParams['params'] = []
    checkParams['params'].push(checkObj)
    checkParams['version'] = TTL_VERSION
    if (map.hasOwnProperty("03")) { //参数 
        if (map['03'] === '90') {
            params['model'] = 'JTY-GD-TCN2010-L'   //型号
        }
        var version = mergeArray(map['05'])
        var asr = (parseInt(version[0]) / 10).toFixed(1);
        var n = (parseInt(version[1]) / 10).toFixed(1);
        params['software_version'] = 'ASR:V' + asr + ';676N:V' + n; //软件版本
        params['heartbeat'] = parseInt(map['06'], 16); //正常工作间隔
        params['emergency_alarm_interval'] = parseInt(map['08'], 16);  //紧急报警间隔
        params['general_alarm_interval'] = parseInt(map['09'], 16);  //一般报警间隔
        params['concentration_threshold'] = parseInt(map['14'], 16); //烟雾浓度阈值
        params['temperature_alarm_up'] = parseInt(map['0B'], 16); //温度报警值上限
        params['temperature_alarm_down'] = hexTransform(map['21']);  //温度报警值下限
        jsonMap['method'] = TTL_EVENT_PARAMS;
    } else if (map.hasOwnProperty("24")) { //状态数据
        params['smoke_concentration'] = parseInt(map['14'], 16) // 烟雾浓度值
        params['temperature_value'] = hexTransform(map['0B']) // 温度值
        params['battery'] = parseInt(map['24'], 16) // 电池电量
        if (map.hasOwnProperty("19")) {
            params['device_self_check'] = parseInt(map['19'], 16) // 设备自检
        }
        if (map['01'] === '01') {
            params['deviceState'] = '火灾报警' // 设备状态--火灾报警、心跳
            params['deviceStateCode'] = '1001001' // 设备状态Code码
            params['level'] = 1 // 事件级别
            jsonMap['method'] = TTL_EVENT_ALARMS;
        } else if (map['01'] === '02'){ // 设备状态（温度超限报警）
            params['deviceState'] = '温度超限' // 设备状态--温度报警 -- 20221226 非故障状态
            // params['deviceStateCode'] = '1001014'
            // params['level'] = 1
            jsonMap['method'] = TTL_EVENT_OTHER;
        } else if (map['02'] === '02'){ // 设备状态（欠压报警）
            params['battery'] = parseInt(map['24'], 16); //欠压报警 广中医低 电压改为故障 - 低电压报警/1002002
            params['deviceState'] = '欠压报警' // 设备状态--欠压报警
            params['deviceStateCode'] = '1001027'
            params['level'] = 1
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (map['02'] === '03'){ // 设备状态（防拆报警）
            params['deviceState'] = '防拆报警' // 设备状态--防拆报警
            params['deviceStateCode'] = '1002003'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else if (map['02'] === '07'){ // 设备状态（通信故障）
            params['deviceState'] = '通信故障' // 设备状态--通信故障
            params['deviceStateCode'] = '1002074'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS;
        } else {
            if (map['19'] == '01') {
                jsonArr.push(checkParams)
                return JSON.stringify(jsonArr);
            } else {
                params['deviceState'] = '心跳上报' // 设备状态--心跳
                jsonMap['method'] = TTL_EVENT_HEARTBEAT;
            }
        }
        if (map['19'] == '01') jsonArr.push(checkParams)
    } else if (map.hasOwnProperty('19')) { //自检
        params = checkParams
    } else if (map.hasOwnProperty('16')) { //上行包计数
        params['report_count'] = parseInt(map['16'].substring(4, 8), 16);//上报计数
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty('17')) { //下行查询
        params['failed_frame_count'] = parseInt(map['17'], 16); //发送失败帧计数
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("06")) { // 下行应答
        params['heartbeat'] = parseInt(map['06'], 16); //正常工作间隔
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("08")) { // 下行应答
        params['emergency_alarm_interval'] = parseInt(map['08'], 16);  //紧急报警间隔
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("09")) { // 下行应答
        params['general_alarm_interval'] = parseInt(map['09'], 16);  //一般报警间隔
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("14")) { // 下行应答
        params['concentration_threshold'] = parseInt(map['14'], 16) // 烟雾浓度阈值
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("0B") || map.hasOwnProperty("21")) { // 下行应答
        params['temperature_alarm_up'] = parseInt(map['0B'], 16); //温度值报警值上限
        params['temperature_alarm_down'] = hexTransform(map['21']);  //温度报警值下限
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else if (map.hasOwnProperty("20")) { // 下行应答
        params['buzzer_silence_time'] = parseInt(map['20'], 16); //蜂鸣器消音
        jsonMap['method'] = TTL_EVENT_OTHER;
    } else { // 其它
        jsonMap['method'] = TTL_EVENT_OTHER;
    }
    jsonMap['version'] = TTL_VERSION;
    if (Object.keys(params).length) jsonMap['params'] = [params];
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
        deviceRestart: [0x0C, 01, 01],
        resumeFactory: [0x0D, 01, 01],
        buzzerSilenceTime: [0x20, 04, ''],
        heartbeat: [0x06, 04, ''],
        emergencyTime: [0x08, 04, ''],
        generalAlarmTime: [0x09, 04, ''],
        concentrationThreshold: [0x14, 02, ''],
        upperTemperatureLimit: [0x0B, 01, ''],
        lowerTemperatureLimit: [0x21, 01, ''],
        reportCount: [0x16, '', ''],
        reportFailedFrameCount: [0x17, '', '']
    }
    // 消息体参数
    var paramsMethod = {}
    // 消息体参数 - 控制
    paramsMethod[TTL_SERVICE_CONTROL_DEVICE_RESTART] = ['deviceRestart']
    paramsMethod[TTL_SERVICE_CONTROL_RESUME_FACTORY] = ['resumeFactory']
    paramsMethod[TTL_SERVICE_CONTROL_BUZZER_SILENCE_TIME] = ['buzzerSilenceTime']
    // 消息体参数 - 配置
    paramsMethod[TTL_SERVICE_SET_HEARTBEAT] = ['heartbeat']
    paramsMethod[TTL_SERVICE_SET_EMERGENCY_TIME] = ['emergencyTime']
    paramsMethod[TTL_SERVICE_SET_GENERAL_ALARM_TIME] = ['generalAlarmTime']
    paramsMethod[TTL_SERVICE_SET_TEMPERATURE_LIMIT] = ['upperTemperatureLimit', 'lowerTemperatureLimit']
    // 消息体参数 - 查询
    paramsMethod[TTL_SERVICE_GET_HEARTBEAT] = ['heartbeat']
    paramsMethod[TTL_SERVICE_GET_EMERGENCY_TIME] = ['emergencyTime']
    paramsMethod[TTL_SERVICE_GET_GENERAL_ALARM_TIME] = ['generalAlarmTime']
    paramsMethod[TTL_SERVICE_GET_CONCENTRATION_THRESHOLD] = ['concentrationThreshold']
    paramsMethod[TTL_SERVICE_GET_TEMPERATURE_LIMIT] = ['upperTemperatureLimit', 'lowerTemperatureLimit']
    paramsMethod[TTL_SERVICE_GET_REPORT_COUNT] = ['reportCount']
    paramsMethod[TTL_SERVICE_GET_REPORT_FAILED_FRAME_COUNT] = ['reportFailedFrameCount']
    var msgBody = new Array();
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
                        if (typeof paramsVal == 'string') {
                            paramsVal = parseInt(paramsVal)
                        }
                        temp = Str2Bytes(PrefixInteger(paramsVal.toString(16), parseInt(Length) * 2))
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
