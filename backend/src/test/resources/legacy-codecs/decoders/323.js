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

// es6 Object.assign
function ObjectAssign(target, source) {
    for (var i in source) {
        target[i] = source[i]
    }
    return target
}

// 获取事件数据
function getEvenData(index, type) {
    var evenArr = ['烟感故障报警', '', '低电压报警', '']
    var evenCodeArr = ['1002001', '', '1002002', '']
    var obj = {}
    obj['level'] = 2
    if (type == 'tamper_alarm') { // 防拆报警 serviceId 1001
        obj['deviceState'] = "防拆报警",
        obj['deviceStateCode'] = "1002006"
    } else { // 故障上报 serviceId 1002
        obj['deviceState'] = evenArr[index - 1],
        obj['deviceStateCode'] = evenCodeArr[index - 1]
    }
    return obj
}

//解析设备上传的原始数据
function rawDataToProtocol(params) {
    var jsonMap = {};
    var jsonArr = [];
    var method = "";
    var param = {};
    // 事件上报 服务ID
    var serviceIdArr = {
        "1": {
            method: TTL_EVENT_OTHER
        },
        "2": {
            method: TTL_EVENT_HEARTBEAT,
            deviceState: "心跳上报"
        },
        "5": {
            method: TTL_EVENT_PARAMS
        },
        "1003": {
            method: TTL_EVENT_ALARMS,
            deviceState: "火灾报警",
            deviceStateCode: "1001001",
            level: 1
        },
        "1005": {
            method: TTL_EVENT_OTHER,
            deviceState: "温度超限",
            // deviceStateCode: "1002031",
            // level: 2
        }
    }
    param['deviceState'] = '正常'; // 设备状态默认正常
    var keyValue = params['messageType'] // 消息类型
    var serviceId = params['serviceId'] // 服务ID
    var eventContent = params['eventContent'] // 消息数据
    if (keyValue == 'dataReport' && (serviceId == 1 || serviceId == 2 || serviceId == 5)) {
        method = serviceIdArr[serviceId]['method'];
        var paramsData = {}
        if (method == TTL_EVENT_HEARTBEAT) {
            var battery_value = params['payload']['battery_value']
            var terminal_type = params['payload']['terminal_type']
            var heartbeat_time = params['payload']['heartbeat_time']
            var cell_ID = params['payload']['cell_ID']
            params['payload']['heartbeat'] = parseInt(heartbeat_time)
            params['payload']['battery'] = battery_value
            params['payload']['model'] = terminal_type
            params['payload']['cell_id'] = cell_ID
            params['payload']['deviceState'] = '心跳上报';
            paramsData = params['payload']
        } else if (method == TTL_EVENT_PARAMS) {
            var heart = params['payload']['heart']
            params['payload']['heartbeat'] = parseInt(heart)
            paramsData = params['payload']
        } else {
            var smokeValue = params['payload']['smoke_value']
            if (smokeValue < 100) {
                paramsData = params['payload']
            } else {
                method = TTL_EVENT_ALARMS;
                serviceId = "1003"
                paramsData = ObjectAssign(params['payload'], {
                    deviceState: serviceIdArr[serviceId]['deviceState'],
                    deviceStateCode: serviceIdArr[serviceId]['deviceStateCode'],
                    level: serviceIdArr[serviceId]['level']
                })
            }
        }
        param = ObjectAssign(param, paramsData)
    } else if (keyValue == 'eventReport') {
        var evenObj = {}
        param = ObjectAssign(param, eventContent || {})
        if (serviceId == '1001') { // 防拆报警
            if (eventContent['tamper_alarm'] == 1) {
                evenObj = getEvenData(eventContent['tamper_alarm'], 'tamper_alarm')
                param = ObjectAssign(param, evenObj)
                method = TTL_EVENT_FAULTS
            } else {
                method = TTL_EVENT_OTHER
            }
        } else if (serviceId == '1002') { // 故障上报
            evenObj = getEvenData(eventContent['error'], 'error')
            if (evenObj['deviceState'] == '') {
                method = TTL_EVENT_OTHER
            } else {
                param = ObjectAssign(param, evenObj)
                method = TTL_EVENT_FAULTS
            }
        } else if (serviceId == '1003') { // 烟感监测状态报警
            var smokeValue = eventContent['smoke_value'];
            if (smokeValue < 100) {
                method = TTL_EVENT_OTHER
            } else {
                param = ObjectAssign(param, {
                    deviceState: serviceIdArr[serviceId]['deviceState'],
                    deviceStateCode: serviceIdArr[serviceId]['deviceStateCode'],
                    level: serviceIdArr[serviceId]['level']
                })
                method = serviceIdArr[serviceId]['method'];
            }
        } else {
            param = ObjectAssign(param, {
                deviceState: serviceIdArr[serviceId]['deviceState'],
                deviceStateCode: serviceIdArr[serviceId]['deviceStateCode'],
                level: serviceIdArr[serviceId]['level']
            })
            method = serviceIdArr[serviceId]['method'];
        }
    } else {
        method = TTL_EVENT_OTHER
        if (params['payload']) {
            param = ObjectAssign(param, params['payload'])
        } else {
            param = ObjectAssign(param, params)
        }
    }
    var battery_voltage = param['battery_voltage']
    var rsrp = param['rsrp']/10
    var sinr = param['sinr']/10
    if (rsrp) { param['rsrp'] = parseFloat(Number(rsrp).toFixed(1)); }
    if (sinr) { param['sinr'] = parseFloat(Number(sinr).toFixed(1)); }
    if (battery_voltage) { param['battery_voltage'] = parseFloat(Number(battery_voltage).toFixed(2)); }
    param['temperature_value'] = param['temp_val_alarm']
    param['battery'] = param['battery_value']
    param = removePropertyOfNull(param)
    jsonMap['method'] = method;
    jsonMap['params'] = [param];
    jsonMap['version'] = TTL_VERSION;
    jsonArr.push(jsonMap)
    return JSON.stringify(jsonArr);
}

function protocolToRawData(params) {}

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

// 告警 & 故障
// aep_n1010_tc
// 烟感故障报警 1002001 
// 低电压报警 1002002
// 防拆报警 1002006
// 火灾报警 1001001
// 温度超限报警 1002031
