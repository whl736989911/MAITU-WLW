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
var TTL_SERVICE_SET_TEMPERATURE_LIMIT = 'thing.service.setTemperatureLimit'; //温度值阈值上、下限
// 控制数据 指令码04
var TTL_SERVICE_CONTROL_DEVICE_RESTART = 'thing.service.controlDeviceRestart'; //设备重启  1 有效
var TTL_SERVICE_CONTROL_RESUME_FACTORY = 'thing.service.controlResumeFactory'; //恢复出厂 1 有效
var TTL_SERVICE_CONTROL_BUZZER_SILENCE_TIME = 'thing.service.controlBuzzerSilenceTime'; //蜂鸣器消音 86400s-30s
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

// es6 Object.assign
function ObjectAssign(target, source) {
    for (var i in source) {
        target[i] = source[i]
    }
    return target
}

//消息体解析数据成key=>value
function parseData2KeyValue(uint8Array) {
    var data = uint8Array.subarray(12, -3);
    var map = {};
    var begin = 0;
    for (var index = 0; index < data.length; index++) {
        var key = data[begin].toString(16);
        if (key.length < 2)
            key = '0' + key;
        var keyLen = data[begin + 1];
        var end = begin + 2 + keyLen;
        var value = dec2Hex(data.subarray(begin + 2, end));
        // 多种告警或故障类消息处理
        if (key == '01' || key == '02') {
            key = key + value
        }
        map[key.toUpperCase()] = value;
        begin = end;
        if (begin >= data.length)
            break;
    }
    return map;
}
function dec2Hex(array) {
    var str = '';
    for (var index = 0; index < array.length; index++) {
        var element = array[index].toString(16);
        if (element.length < 2)
            element = "0" + element;
        str = str.concat(element);
    }
    return str;
}

// 16进制转有符号的10进制整数
function hexTransform(value) {
    var two = parseInt(value, 16).toString(2);
    var bitNum = value.length*4;
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

function HexToBase64(sha1) {
    var digits = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    var base64_rep = ""
    var ascv
    var bit_arr = 0
    var bit_num = 0
  
    for (var n = 0; n < sha1.length; ++n) {
      if (sha1[n] >= 'A' && sha1[n] <= 'Z') {
        ascv = sha1.charCodeAt(n) - 55
      } else if (sha1[n] >= 'a' && sha1[n] <= 'z') {
        ascv = sha1.charCodeAt(n) - 87
      } else {
        ascv = sha1.charCodeAt(n) - 48
      }
  
      bit_arr = (bit_arr << 4) | ascv
      bit_num += 4
      if (bit_num >= 6) {
        bit_num -= 6
  
        base64_rep += digits[bit_arr >>> bit_num]
        bit_arr &= ~ (-1 << bit_num)
      }
    }
  
    if (bit_num > 0) {
      bit_arr <<= 6 - bit_num
      base64_rep += digits[bit_arr]
    }
    var padding = base64_rep.length % 4
  
    if (padding > 0) {
      for (var n = 0; n < 4 - padding; ++n) {
        base64_rep += "="
      }
    }
    return base64_rep
}

var Base64 = {
    _keyStr: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=",
    encode: function(e) {
      var t = "";
      var n, r, i, s, o, u, a;
      var f = 0;
      while (f < e.length) {
        n = e.charCodeAt(f++);
        r = e.charCodeAt(f++);
        i = e.charCodeAt(f++);
        s = n >> 2;
        o = (n & 3) << 4 | r >> 4;
        u = (r & 15) << 2 | i >> 6;
        a = i & 63;
        if (isNaN(r)) {
          u = a = 64
        } else if (isNaN(i)) {
          a = 64
        }
        t = t + this._keyStr.charAt(s) + this._keyStr.charAt(o) + this._keyStr.charAt(u) + this._keyStr.charAt(a)
      }
      return t
    },
    decode: function(e) {
      var t = "";
      var n, r, i;
      var s, o, u, a;
      var f = 0;
      e = e.toString().replace(/[^A-Za-z0-9+/=]/g, "");
      while (f < e.length) {
        s = this._keyStr.indexOf(e.charAt(f++));
        o = this._keyStr.indexOf(e.charAt(f++));
        u = this._keyStr.indexOf(e.charAt(f++));
        a = this._keyStr.indexOf(e.charAt(f++));
        n = s << 2 | o >> 4;
        r = (o & 15) << 4 | u >> 2;
        i = (u & 3) << 6 | a;
        t = t + String.fromCharCode(n);
        if (u != 64) {
          t = t + String.fromCharCode(r)
        }
        if (a != 64) {
          t = t + String.fromCharCode(i)
        }
      }
      return t
    }
}

function base64toHEX(base64) {
    var raw = Base64.decode(base64);
    var HEX = '';
    for ( i = 0; i < raw.length; i++ ) {
        var _hex = raw.charCodeAt(i).toString(16)
        HEX += (_hex.length==2?_hex:'0'+_hex);
    }
    return HEX.toUpperCase();
}
  
function HexString2Bytes(str) {
    var pos = 0;
    var len = str.length;
    if (len % 2 != 0) {
        return null;
    }
    len /= 2;
    var arrBytes = new Array();
    for (var i = 0; i < len; i++) {
        var s = str.substr(pos, 2);
        var v = parseInt(s, 16);
        arrBytes.push(v);
        pos += 2;
    }
    return arrBytes;
}

//解析设备上传的原始数据
function rawDataToProtocol(uploadData) {
    var params = {};
    var jsonMap = {};
    var jsonArr = [];
    var keyValue = uploadData['messageType'] // 消息类型
    if (keyValue == 'dataReport') { // 数据上报
        var appData = uploadData['payload']['APPdata'] // 数据
        var hex = base64toHEX(appData);
        var newArr = HexString2Bytes(hex);
        var uint8Array = new Uint8Array(newArr.length)
        for (var i = 0; i < newArr.length; i++) {
            uint8Array[i] = newArr[i] & 0xff
        }
        var dataView = new DataView(uint8Array.buffer, 0);
        var map = parseData2KeyValue(uint8Array);
        params['device_self_check'] = 0 //设备自检状态默认为0
        params['payload'] = hex; // 原始数据
        params['frame_protocol_ver'] = dataView.getUint8(1).toString(16) //协议版本
        params['fcnt'] = dataView.getUint16(6) //帧号
        var rtData = {
            '65': ['IMEI'],
            // 参数数据
            '66': ['IMSI'], // SIM卡唯一标识码
            '67': ['CMSV', 'hexCharCodeToStr'], // 通信模组软件版本
            '68': ['nb_module_manufacturer_number', 'nb_module'], // nb 模块厂商编号
            '03': ['device_type'], // 终端类型
            '05': ['software_version', 'version'], // 软件版本
            '06': ['heartbeat', 'parseInt'], // 正常工作时间间隔
            '08': ['emergency_alarm_interval', 'parseInt'], // 报警时隔
            '09': ['general_alarm_interval', 'parseInt'], // 一般报警时隔
            // '14': ['concentration_threshold', 'smoke_concentration', 'parseInt'], // 烟雾浓度阈值
            // '0B': ['temperature_alarm_up', 'parseInt'], // 温度报警值上限
            '21': ['temperature_alarm_down', 'hexTransform'], // 温度报警值下限
            // 状态数据
            '69': ['rssi', 'hexTransform'], // NB信号质量RSSI(有符号数)
            // '0B': ['temperature_value', 'hexTransform'], // 温度
            '24': ['battery', 'parseInt'], // 电池电量
            '6C': ['rsrp', 'hexTransformDecimal'], // rsrp 参考信号接收功率
            '6D': ['sinr', 'hexTransformDecimal'], // sinr 信噪比
            '6E': ['pci', 'parseInt'], // pci 物理小区标识
            '6F': ['ecl', 'parseInt'], // ecl NB模组网络质量打分值覆盖等级
            '70': ['ICCID'], // ICCID
            '71': ['cell_id', 'parseInt'], // CellID
            '16': ['report_count', 'report_alarm_count', 'report_heartbeat_count'], // 上报包总计数、上报报警帧总计数、心跳帧总计数
            // params['report_count'] = parseInt(map['16'].substring(4, 8), 16);//上报计数
            '17': ['failed_frame_count', 'parseInt'], // 发送失败帧计数
            '19': ['device_self_check', 'hexTransform'], // 设备自检
            // 下行回复
            '20': ['buzzer_silence_time', 'parseInt'], // 蜂鸣器消音时间
            '6A': ['ip_address', 'ipAddress'], // IP地址
            '6B': ['port', 'parseInt'], // 端口
            // 报警故障
            '0101': ['火灾报警', '1001001', 1],
            // '0102': ['温度报警', '1002083', 2],
            '0202': ['烟感低电量报警', '1002027', 2],
            '0203': ['防拆报警', '1002003', 2]
        }
        var alarmsObj = JSON.parse(JSON.stringify(params))
        var faultsObj = JSON.parse(JSON.stringify(params))
        for (var i in map) {
            var val = map[i]
            var _index = i
            var rtItem = rtData[_index]
            if (_index in rtData) {
                var key = rtItem[0]
                var as = rtItem[1]
                var ass = rtItem[2]
                if (rtItem[1] == val) val = rtItem[2]
                if (as == 'ipAddress') val = Str2Bytes(val).join("."); // IP地址
                if (as == 'nb_module') val = val == '0304' ? '利尔达MB26': '利尔达CAT1'; // nb 模块厂商编号
                if (as == 'version') val = ((val) / 10).toFixed(1);
                if (as == 'parseInt') val = parseInt(val, 16)
                if (as == 'hexCharCodeToStr') val = hexCharCodeToStr(val)
                if (as == 'hexTransform') val = hexTransform(val)
                if (as == 'hexTransformDecimal') val = (hexTransform(val) / 10).toFixed(1)
                // 去点IMEI 前面的0
                if (i == '65') val = val.slice(1)
                if (i == '16') { // 上报包总计数、上报报警帧总计数、心跳帧总计数
                    params[as] = parseInt(val.substr(8, 8), 16);
                    params[ass] = parseInt(val.substr(16, 8), 16);
                    val = parseInt(val.substr(0, 8), 16);
                }
                if (i == '03') { // 终端类型
                    var dTypeObj = { "17": "JTY-GD-TCN2010", "98": "JTY-GD-TCN2010-C" }
                    val = dTypeObj[val]
                }
                var alarmCodesArr = ['0101']; // 告警码
                var faultCodesArr = ['0202', '0203']; // 故障码
                // 报警&故障
                if (alarmCodesArr.indexOf(_index) > -1 || faultCodesArr.indexOf(_index) > -1) {
                    if ('0B' in map) params['temperature_value'] = hexTransform(map['0B']) // 温度值
                    params['smoke_concentration'] = parseInt(map['14'], 16) // 烟雾浓度值
                    var deviceState = rtData[_index][0]
                    var deviceStateCode = rtData[_index][1]
                    var level = rtData[_index][2]
                    var afObj = {
                        deviceState: deviceState,
                        deviceStateCode: deviceStateCode,
                        level: level
                    }
                    params = ObjectAssign(params, afObj)
                    params = removePropertyOfNull(params)
                    var _params = ObjectAssign({}, params)
                    if (alarmCodesArr.indexOf(_index) > -1) {
                        // 报警
                        alarmsObj = ObjectAssign(_params, afObj)
                        jsonArr.push({
                            method: TTL_EVENT_ALARMS,
                            params: [alarmsObj]
                        })
                    } else if (faultCodesArr.indexOf(_index) > -1) {
                        // 故障
                        faultsObj = ObjectAssign(_params, afObj)
                        jsonArr.push({
                            method: TTL_EVENT_FAULTS,
                            params: [faultsObj]
                        })
                    }
                } else {
                    params[key] = val
                    alarmsObj[key] = val
                    faultsObj[key] = val
                }
            }
        }
        var isParams = '03' in map; // 参数
        var isNoFaults = '0102' in map; // 非故障
        var isAlarms = '0101' in map || '0202' in map || '0203' in map; // 告警或故障
        var isHeartbeat = ('24' in map) && !isAlarms && !isNoFaults; // 心跳
        var isOther = '19' in map || (!isParams && !isHeartbeat && !isAlarms); // 其它
        if ('21' in map) { // 有下限值就有上限值
            params['temperature_alarm_up'] = parseInt(map['0B'], 16); // 温度报警值上限
        } else {
            if ('0B' in map) params['temperature_value'] = hexTransform(map['0B']) // 温度值
        }
        if (isOther) { // 其它
            jsonMap['method'] = TTL_EVENT_OTHER;
            if (map['0102'] == 2) params['deviceState'] = '温度超限'
        }
        if (isParams) {
            params['concentration_threshold'] = parseInt(map['14'], 16); // 烟雾浓度阈值
            jsonMap['method'] = TTL_EVENT_PARAMS; // 参数
        }
        if (isHeartbeat) { // 心跳
            params['smoke_concentration'] = parseInt(map['14'], 16) // 烟雾浓度值
            params['deviceState'] = '心跳上报'
            jsonMap['method'] = TTL_EVENT_HEARTBEAT;
            if (isOther && map['19'] == 1) {
                jsonMap['method'] = TTL_EVENT_OTHER;
                params['deviceState'] = '设备自检'
            }
        }
        if (!isAlarms) {
            params = removePropertyOfNull(params)
            jsonMap['params'] = [params];
            jsonArr.push(jsonMap)
        }
    } else {
        jsonMap['method'] = TTL_EVENT_OTHER;
        var param = ObjectAssign({}, uploadData)
        param = removePropertyOfNull(param)
        jsonMap['params'] = [param];
        jsonArr.push(jsonMap)
    }
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
    paramsMethod[TTL_SERVICE_SET_TEMPERATURE_LIMIT] = ['upperTemperatureLimit', 'lowerTemperatureLimit']
    // 消息体参数 - 控制
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

// 删除对象中所有值为null或非零的属性
function removePropertyOfNull (obj) {
    var newObj = {}
    var arr = Object.keys(obj)
    for (var i in arr) {
        // 判断字符串是否为空
        if (!(typeof obj[arr[i]] == 'undefined' || obj[arr[i]] === null || obj[arr[i]] === '')) {
            newObj[arr[i]] = obj[arr[i]]
        }
    }
    return newObj;
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
    var bufferStr = buffer.toString()
    var start = 0
    var end = 2
    var index = 0
    var list = []
    // var [start, end, index, list] = [0, 2, 0, []]
    for (var i in bufferStr) {
        start = index
        end = index + 2
        var item = bufferStr.slice(start, end)
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

function buffer_int16(value) {
    var uint8Array = new Uint8Array(2);
    var dv = new DataView(uint8Array.buffer, 0);
    dv.setInt16(0, value);
    return [].slice.call(uint8Array);
}
