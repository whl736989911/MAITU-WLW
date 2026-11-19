var TTL_VERSION = '2.0'
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
var TTL_SERVICE_CONTROL_SUA = 'thing.service.controlSUA' // 设置停止紧急报警标志消音周期
var TTL_SERVICE_CONTROL_SNUA = 'thing.service.controlSNUA' // 设置停止非紧急报警标志消音周期

// 服务(下行参数命令帧)
var TTL_SERVICE_SET_SERVER_IP_PORT = 'thing.service.setServerIPPort' // 设置IP地址和端口
var TTL_SERVICE_SET_NUAI = 'thing.service.setNUAI' // 设置非紧急报警上报间隔标志
// var TTL_SERVICE_SET_SM = 'thing.service.setSM' // 设置报警器型号
var TTL_SERVICE_SET_UAI = 'thing.service.setUAI' // 设置紧急报警上报间隔标志
var TTL_SERVICE_SET_HBI = 'thing.service.setHBI' // 设置工作状态上报间隔标志
var TTL_SERVICE_SET_MDI = 'thing.service.setMDI' // 设置无线底座检测间隔
var TTL_SERVICE_SET_TT = 'thing.service.setTT' // 设置底座温度检测阈值标志
var TTL_SERVICE_SET_RESTORE = 'thing.service.setRestore' // 设置恢复终端出厂设置并重启标志
var TTL_SERVICE_SET_RESET = 'thing.service.setReset' // 设置复位并重启终端标志

// 服务(下行链路状态确认帧)
var TTL_SERVICE_LSC_ACK = 'thing.service.LSCACK' // 设置链路状态确认

//服务（下行包计数帧）
var TTL_SERVICE_PACKAGE_COUNT = 'thing.service.packageCount' //下行包计数帧

//服务（下行IMSI帧）
var TTL_SERVICE_IMSI = 'thing.service.imsi' //下行IMSI帧



// 服务(下行控制帧) V1.2
var TTL_SERVICE_SILENT_TIME_OLD = 'thing.service.SilentTimeOld' // 设置静默周期
var TTL_SERVICE_STOP_FIRE_ALARM = 'thing.service.StopFireAlarm' // 设置停止火灾报警标志
var TTL_SERVICE_STOP_TEMP_ALARM = 'thing.service.StopTempAlarm' // 设置停止底座温度超限报警标志
var TTL_SERVICE_SMFA = 'thing.service.SMFA' // 设置停止无线底座的故障报警标志

// 服务(下行参数命令帧) V1.2
var TTL_SERVICE_SETAI = 'thing.service.SetAI' // 设置紧急报警上报间隔标志


// 帧类型
var FRAME_TYPE_PARAM = 0xd3 // 上行参数帧
var FRAME_TYPE_ALARM_STATE = 0xd8 // V1.3 上行状态帧 报警
var FRAME_TYPE_WORK_STATE = 0xd9 // V1.3 上行状态帧 心跳

var FRAME_TYPE_ALARM_STATE_OLD = 0xd1 // V1.2 上行状态帧 报警
var FRAME_TYPE_WORK_STATE_OLD = 0xd2 // V1.2 上行状态帧 心跳

var FRAME_TYPE_RESPONSE = 0xf5 // 上行状态应答
var FRAME_TYPE_LINK_STATE_REQUEST = 0xf0 // 上行链路状态查询帧
var FRAME_TYPE_PACKAGE_COUNT = 0xd6 // 上行包计数帧
var FRAME_TYPE_UP_IMSI = 0xf3 //上行IMSI帧
var FRAME_TYPE_UPGRADE = 0xd7 //上行升级帧

//解析设备上传的原始数据
function rawDataToProtocol(bytes) {
    var uint8Array = new Uint8Array(bytes.length)
    for (var i = 0; i < bytes.length; i++) {
        uint8Array[i] = bytes[i] & 0xff
    }
    var params = {}
    var jsonMap = {}
    var jsonArr = []
    var dataView = new DataView(uint8Array.buffer, 0)
    var fhead = uint8Array[0]
    var imei = Bytes2HexString(uint8Array.subarray(1, 9))
    var FrameType = fhead.toString(16).toUpperCase() //帧类型

    // 回复终端ack
    var ACKparams = {}
    ACKparams['method'] = TTL_SERVICE_LSC_ACK
    ACKparams['params'] = {
        "IMEI": imei,
        "FnCt": 1
    }
    ACKparams['version'] = TTL_VERSION
    if (fhead == FRAME_TYPE_PARAM) {
        //参数
        params['IMEI'] = imei //IMEI
        params['frame_type'] = FrameType //帧类型
        params['CMSV'] = Bytes2HexString(uint8Array.subarray(9, 15)) //通信模组软件版本
        params['nb_module_manufacturer_number'] = setDec2Hex(uint8Array, 15, 17) // NB模块厂商编号
        params['terminal_type'] = getMoteType(uint8Array[17]) //终端类型
        params['model'] = getModel(uint8Array[18]) //型号
        params['hardware_version'] = parseInt((uint8Array[19] & 0xf0) >> 4, 16) //硬件版本
        params['software_version'] = parseInt(uint8Array[19] & 0x0f, 16) //软件版本
        params['heartbeat'] = timeInterval((uint8Array[20] & 0xf0) >> 4, 'heartbeat') // 工作状态定时上报间隔
        params['wireless_base_detect_interval'] = timeInterval(uint8Array[20] & 0x0f, 'MDetectInterval') // 无线底座检测间隔
        params['emergency_alarm_interval'] = timeInterval(uint8Array[21], 'UrgentAlarmInterval') // 紧急报警上报间隔
        params['general_alarm_interval'] = timeInterval(uint8Array[24], 'NotUrgentAlarmInterval') //一般报警上报间隔设置
        params['temperature_alarm_down'] = dataView.getInt8(22) //底座温度超限阀值下限设置
        params['temperature_alarm_up'] = dataView.getInt8(23) //底座温度超限阀值上限设置
        jsonMap['method'] = TTL_EVENT_PARAMS
    } else if (fhead == FRAME_TYPE_ALARM_STATE) {
        //报警和故障
        if (uint8Array[14] & 0x08) {
            params['deviceState'] = '火灾报警' // 设备状态--火灾报警
            params['deviceStateCode'] = '1001001'
            params['level'] = 1
            jsonMap['method'] = TTL_EVENT_ALARMS
        } else if (uint8Array[14] & 0x01) { // 20221226 改为非故障状态
            params['deviceState'] = '无线底座温度超限' // 设备状态--无线底座温度超限报警
            // params['deviceStateCode'] = '1002012'
            // params['level'] = 2
            jsonMap['method'] = TTL_EVENT_OTHER
        } else if (uint8Array[15] & 0x10) {
            params['deviceState'] = '无线底座防拆报警' // 设备状态--无线底座防拆报警
            params['deviceStateCode'] = '1002006'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[15] & 0x04) {
            params['deviceState'] = '无线底座温度传感器故障报警' // 设备状态--无线底座温度传感器故障报警
            params['deviceStateCode'] = '1002008'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[15] & 0x02) {
            params['deviceState'] = '无线底座低电压报警' // 设备状态--无线底座低电压报警
            params['deviceStateCode'] = '1002009'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[15] & 0x01) {
            params['deviceState'] = '无线底座其他故障报警' // 设备状态--无线底座其他故障报警
            params['deviceStateCode'] = '1002010'
            params['level'] = 3
            jsonMap['method'] = TTL_EVENT_FAULTS
        }
    } else if (fhead == FRAME_TYPE_WORK_STATE) {
        //心跳
        var ACK = (uint8Array[14] & 0x80) >> 7
        params['IMEI'] = imei //IMEI
        params['frame_type'] = FrameType
        params['rssi'] = dataView.getInt8(9)
        params['fcnt'] = dataView.getUint16(10)
        params['failed_frame_count'] = dataView.getUint16(12)
        params['ACK'] = ACK
        params['temperature_value'] = dataView.getInt8(16)
        params['voltage'] = dataView.getInt16(17) / 100
        params['rsrp'] = dataView.getInt16(19) / 10
        params['sinr'] = dataView.getInt16(21) / 10
        params['pci'] = dataView.getUint16(23)
        params['ecl'] = dataView.getUint8(25)
        params['battery'] = dataView.getUint8(26)
        params['ICCID'] = Bytes2HexString(uint8Array.subarray(27, 37))
        params['cell_id'] = dataView.getUint32(37)
        params['deviceState'] = '心跳上报' // 心跳上报
        jsonMap['method'] = TTL_EVENT_HEARTBEAT
        if (ACK)
            jsonMap['ack'] = ACKparams;
    } else if (fhead == FRAME_TYPE_ALARM_STATE_OLD) {
        //报警和故障
        if (uint8Array[13] & 0x08) {
            params['deviceState'] = '火灾报警' // 设备状态--火灾报警
            params['deviceStateCode'] = '1001001'
            params['level'] = 1
            jsonMap['method'] = TTL_EVENT_ALARMS
        } else if (uint8Array[13] & 0x01) {
            params['deviceState'] = '无线底座温度超限报警' // 设备状态--无线底座温度超限报警
            params['deviceStateCode'] = '1001002'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[14] & 0x10) {
            params['deviceState'] = '无线底座防拆报警' // 设备状态--无线底座防拆报警
            params['deviceStateCode'] = '1002006'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[14] & 0x04) {
            params['deviceState'] = '无线底座温度传感器故障报警' // 设备状态--无线底座温度传感器故障报警
            params['deviceStateCode'] = '1002008'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[14] & 0x02) {
            params['deviceState'] = '无线底座低电压报警' // 设备状态--无线底座低电压报警
            params['deviceStateCode'] = '1002009'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        } else if (uint8Array[14] & 0x01) {
            params['deviceState'] = '无线底座其他故障报警' // 设备状态--无线底座其他故障报警
            params['deviceStateCode'] = '1002010'
            params['level'] = 2
            jsonMap['method'] = TTL_EVENT_FAULTS
        }
    } else if (fhead == FRAME_TYPE_WORK_STATE_OLD) {
        //心跳
        params['IMEI'] = imei //IMEI
        params['rssi'] = dataView.getInt8(9)
        params['fcnt'] = ''
        params['failed_frame_count'] = dataView.getUint8(12)
        params['ACK'] = ''
        params['temperature_value'] = dataView.getInt8(15)
        params['voltage'] = ''
        params['RSRP'] = ''
        params['SINR'] = ''
        params['PCI'] = ''
        params['ECL'] = ''
        params['battery'] = ''
        params['ICCID'] = ''
        params['CellID'] = ''
        params['deviceState'] = '心跳上报' // 心跳上报
        jsonMap['method'] = TTL_EVENT_HEARTBEAT
    } else if (
        fhead == FRAME_TYPE_RESPONSE ||
        fhead == FRAME_TYPE_LINK_STATE_REQUEST ||
        fhead == FRAME_TYPE_PACKAGE_COUNT ||
        fhead == FRAME_TYPE_UP_IMSI ||
        fhead == FRAME_TYPE_UPGRADE
    ) {
        //其它
        params['IMEI'] = imei //IMEI
        params['frame_type'] = FrameType // 帧类型
        jsonMap['method'] = TTL_EVENT_OTHER
    }
    jsonMap['version'] = TTL_VERSION;
    jsonMap['params'] = [params];
    jsonArr.push(jsonMap)
    return JSON.stringify(jsonArr);
}

//下行命令
function protocolToRawData(json) {
    var method = json['method']
    var version = json['version']
    var params = json['params']
    var payloadArray = []
    // 帧类型
    var FT_FrameTypeList = [{
        SN: 'control', // 下行控制帧头
        FH: 0xd4,
        Length: 14
    }, {
        SN: 'set', // 下行参数命令帧
        FH: 0xd5,
        Length: 22
    }, {
        SN: 'LSC', // 下行链路状态确认帧头
        FH: 0xf1,
        Length: 11
    }, {
        SN: 'packageCount', // 下行包计数帧
        FH: 0xd7,
        Length: 10
    }, {
        SN: 'imsi', // 下行IMSI帧
        FH: 0xf4,
        Length: 10
    }]
    // 获取对应服务配置
    var FT_CurrentObj = FT_FrameTypeList.filter(function (item) {
        return method.indexOf(item.SN) > -1
    })
    var methodType = FT_CurrentObj[0].SN
    var FPS = FT_CurrentObj[0].FH
    var Length = FT_CurrentObj[0].Length
    // 设置帧类型
    payloadArray = payloadArray.concat(FPS)
    // 设置IMEI
    payloadArray = payloadArray.concat(Str2Bytes(params['IMEI']))
    // 生成完整的长度
    for (var i = 9; i < Length; i++) {
        payloadArray = payloadArray.concat(0x00)
    }
    // 参数对应的位数
    var dataPosition = {
        SilentTime: 9,
        control_SUA: 12,
        control_SNUA: 12,
        ServerIP: 9,
        ServerPort: 13,
        setNUAI: 15,
        setSM: 15,
        setUAI: 15,
        setHBI: 15,
        setMDI: 15,
        setTT: 15,
        setRestore: 15,
        setReset: 15,
        smokeModel: 20,
        UrgentAlarmInterval: 17,
        NotUrgentAlarmInterval: 21,
        heartbeat: 16,
        ModuleDetectInterval: 16,
        TempThrH: 18,
        TempThrL: 19,
        LSC_ACK: 9,
    }
    // 参数对应的默认值 没有取传参
    var defaultData = {
        control_SUA: '00000010',
        control_SNUA: '00000001',
        setNUAI: '10000000',
        setSM: '01000000',
        setUAI: '00100000',
        setHBI: '00010000',
        setMDI: '00001000',
        setTT: '00000100',
        setRestore: '00000010',
        setReset: '00000001',
        smokeModel: '00001111',
        LSC_ACK: '00000001'
    }
    // 参数左移位数
    var positionLeft = {
        SilentTime: 4,
        ModuleDetectInterval: 4,
    }
    // 参数右移位数
    var positionRight = {}
    // （服务）参数集合
    var paramsMethod = {}
    // （服务）下行控制帧头
    paramsMethod[TTL_SERVICE_CONTROL_SUA] = ['SilentTime', 'control_SUA']
    paramsMethod[TTL_SERVICE_CONTROL_SNUA] = ['control_SNUA']
    // （服务）下行参数命令帧
    paramsMethod[TTL_SERVICE_SET_SERVER_IP_PORT] = ['ServerIP', 'ServerPort']
    paramsMethod[TTL_SERVICE_SET_NUAI] = ['setNUAI', 'NotUrgentAlarmInterval']
    // paramsMethod[TTL_SERVICE_SET_SM] = ['setSM', 'smokeModel']
    paramsMethod[TTL_SERVICE_SET_UAI] = ['setUAI', 'UrgentAlarmInterval']
    paramsMethod[TTL_SERVICE_SET_HBI] = ['setHBI', 'heartbeat']
    paramsMethod[TTL_SERVICE_SET_MDI] = ['setMDI', 'ModuleDetectInterval']
    paramsMethod[TTL_SERVICE_SET_TT] = ['setTT', 'TempThrH', 'TempThrL']
    paramsMethod[TTL_SERVICE_SET_RESTORE] = ['setRestore']
    paramsMethod[TTL_SERVICE_SET_RESET] = ['setReset']
    // （服务）下行链路状态确认帧
    paramsMethod[TTL_SERVICE_LSC_ACK] = ['LSC_ACK', 'ACK']
    // （服务）下行包计数帧
    paramsMethod[TTL_SERVICE_PACKAGE_COUNT] = []
    // （服务）下行IMSI帧
    paramsMethod[TTL_SERVICE_IMSI] = []
    var paramsList = paramsMethod[method]
    // 处理服务参数
    for (var i in paramsList) {
        var key = paramsList[i]
        var paramsVal = params[key]
        var positionVal = dataPosition[key]
        var defaultVal = defaultData[key]
        var pLeft = positionLeft[key]
        var pRight = positionRight[key]
        var itemArray = new Array()
        if (defaultVal) {
            // 存在默认值
            itemArray = itemArray.concat(parseInt(defaultVal, 2))
        } else {
            // 不存在默认值
            var temp = '';
            if (key == 'ServerIP') {
                var ipList = paramsVal.split('.')
                for (var i in ipList) {
                    itemArray = itemArray.concat(parseInt(ipList[i]))
                }
            } else if (key == 'ServerPort') {
                if (typeof paramsVal == 'string') {
                    paramsVal = parseInt(paramsVal)
                }
                temp = Str2Bytes(PrefixInteger(paramsVal.toString(16), 4))
                itemArray = itemArray.concat(temp)
            } else {
                if (typeof paramsVal == 'string') {
                    paramsVal = parseInt(paramsVal)
                }
                temp = Str2Bytes(PrefixInteger(paramsVal.toString(16), 2))
                if (pLeft) {
                    itemArray = itemArray.concat(temp << pLeft)
                } else if (pRight) {
                    itemArray = itemArray.concat(1 >> pRight)
                } else {
                    itemArray = itemArray.concat(temp)
                }
            }
        }
        // 替换相应的位里面的参数值
        if (positionVal) {
            for (var i in itemArray) {
                payloadArray[positionVal + parseInt(i)] = itemArray[i]
            }
        }
    }
    // 帧尾
    if (methodType == 'imsi') {
        payloadArray = payloadArray.concat(0xe2)
    } else {
        payloadArray = payloadArray.concat(0xe1)
    }
    return payloadArray
}

// 字节 --> 2 byte
function Str2Bytes(str) {
    var pos = 0
    var len = str.length
    if (len % 2 != 0) {
        return null
    }
    len /= 2
    var hexA = new Array()
    for (var i = 0; i < len; i++) {
        var s = str.substr(pos, 2)
        var v = parseInt(s, 16)
        hexA.push(v)
        pos += 2
    }
    return hexA
}

//字节数组转十六进制字符串，对负值填坑
function Bytes2HexString(arrBytes) {
    var str = ''
    for (var i = 0; i < arrBytes.length; i++) {
        var tmp
        var num = arrBytes[i]
        if (num < 0) {
            //此处填坑，当byte因为符合位导致数值为负时候，需要对数据进行处理
            tmp = (255 + num + 1).toString(16)
        } else {
            tmp = num.toString(16)
        }
        if (tmp.length == 1) {
            tmp = '0' + tmp
        }
        str += tmp
    }
    return str
}

// 时间间隔转换
function timeInterval(val, paramsInterval) {
    let newVal = val.toString(16)
    if (paramsInterval === 'heartbeat') {
        // 工作状态定时上报间隔
        let HBIArray = [30, 3600, 10800, 21600, 43200, 86400, 0] //0代表无穷大不上报
        newVal = HBIArray[val]
    } else if (paramsInterval === 'MDetectInterval') {
        // 无线底座检测间隔
        let MDIArray = [15, 30, 60, 300, 600, 1800, 3600, 0] //0代表无穷大不上报
        newVal = MDIArray[val]
    } else if (paramsInterval === 'UrgentAlarmInterval') {
        // 紧急报警上报间隔  n*30s 30~300s
        newVal = val * 30
    } else if (paramsInterval === 'NotUrgentAlarmInterval') {
        // 一般报警上报间隔设置
        let NUAIArray = [30, 1800, 3600, 7200, 21600, 43200, 86400, 0] //0代表无穷大不上报
        newVal = NUAIArray[val]
    }
    return newVal
}

//终端类型
function getMoteType(MoteType) {
    if (MoteType == 0x1d) return 'TBS-N140'
    else return ''
}

//终端型号
function getModel(Model) {
    if (Model == 0x03) {
        return 'TBS-N140-A'
    } else if (Model == 0x04) {
        return 'TBS-N140-B'
    } else {
        return ''
    }
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



// 消息体长度转换为 4位
function PrefixInteger(num, length) {
    return (Array(length).join('0') + num).slice(-length);
}

// 处理数据 -- 2个字节
function setDec2Hex(arrVal, start, end) {
    let arr = arrVal.subarray(start, end)
    var str = ''
    for (var index = 0; index < arr.length; index++) {
        var element = arr[index].toString(16)
        if (element.length < 2) element = '0' + element
        str = str.concat(element)
    }
    return str
}

//以下是部分辅助函数
function buffer_uint8(value) {
    var uint8Array = new Uint8Array(1)
    var dv = new DataView(uint8Array.buffer, 0)
    dv.setUint8(0, value)
    return [].slice.call(uint8Array)
}

function buffer_int16(value) {
    var uint8Array = new Uint8Array(2)
    var dv = new DataView(uint8Array.buffer, 0)
    dv.setInt16(0, value)
    return [].slice.call(uint8Array)
}

function buffer_int32(value) {
    var uint8Array = new Uint8Array(4)
    var dv = new DataView(uint8Array.buffer, 0)
    dv.setInt32(0, value)
    return [].slice.call(uint8Array)
}

function buffer_float32(value) {
    var uint8Array = new Uint8Array(4)
    var dv = new DataView(uint8Array.buffer, 0)
    dv.setFloat32(0, value)
    return [].slice.call(uint8Array)
}
