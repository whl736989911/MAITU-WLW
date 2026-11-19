import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Space, Switch, Tag, Typography, message } from 'antd'
import { errorMessage } from '../lib/api'
import { hikvisionConfigApi, type HikvisionConfiguration } from '../lib/hikvision-config'
import { hikvisionApi, type HikvisionStatus } from '../lib/hikvision'
import { useSession } from '../lib/store'

type Values = { endpoint: string; app_key: string; app_secret?: string; enabled: boolean; poll_interval_seconds: number; model_scope: string }
const initialEndpoint = 'https://open.hikfirecloud.com'

export function HikvisionConfiguration() {
  const [form] = Form.useForm<Values>()
  const pendingSave = useRef<{ fingerprint: string; key: string } | null>(null)
  const [configuration, setConfiguration] = useState<HikvisionConfiguration | null>(null)
  const [status, setStatus] = useState<HikvisionStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [validating, setValidating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [validation, setValidation] = useState<string | null>(null)
  const permissions = useSession(state => state.permissions)
  const authGeneration = useSession(state => state.authGeneration)
  const canWrite = permissions.includes('integrations:write')
  const saveController = useRef<AbortController | null>(null)
  const validationController = useRef<AbortController | null>(null)

  useEffect(() => {
    const controller = new AbortController()
    const ownerGeneration = authGeneration
    setConfiguration(null); setStatus(null); setError(null); setValidation(null); setLoading(true); setSaving(false); setValidating(false)
    Promise.all([hikvisionConfigApi.get(controller.signal), hikvisionApi.status(controller.signal)])
      .then(([config, runtime]) => {
        if (controller.signal.aborted || useSession.getState().authGeneration !== ownerGeneration) return
        setConfiguration(config)
        setStatus(runtime)
        form.setFieldsValue({
          endpoint: config.endpoint ?? initialEndpoint,
          app_key: '', app_secret: '', enabled: config.enabled,
          poll_interval_seconds: config.poll_interval_seconds || 30,
          model_scope: config.model_scope || '800235',
        })
        setError(null)
      })
      .catch(reason => { if (!controller.signal.aborted && useSession.getState().authGeneration === ownerGeneration) setError(errorMessage(reason)) })
      .finally(() => { if (!controller.signal.aborted && useSession.getState().authGeneration === ownerGeneration) setLoading(false) })
    return () => {
      controller.abort()
      saveController.current?.abort()
      validationController.current?.abort()
      saveController.current = null
      validationController.current = null
      form.setFieldsValue({ endpoint: initialEndpoint, app_key: '', app_secret: '' })
      pendingSave.current = null
    }
  }, [authGeneration, form])

  async function save(values: Values) {
    if (!configuration || !canWrite) return
    const controller = new AbortController()
    saveController.current?.abort()
    saveController.current = controller
    const ownerGeneration = authGeneration
    const ownsResponse = () => !controller.signal.aborted && useSession.getState().authGeneration === ownerGeneration
    setSaving(true); setError(null); setValidation(null)
    try {
      const body = {
        expected_revision: configuration.revision, endpoint: values.endpoint.trim(), app_key: values.app_key?.trim() ?? '',
        app_secret: values.app_secret?.trim() || undefined, enabled: values.enabled,
        poll_interval_seconds: values.poll_interval_seconds, model_scope: values.model_scope.trim(),
      }
      const fingerprint = JSON.stringify({
        expected_revision: body.expected_revision, endpoint: body.endpoint, app_key: body.app_key,
        app_secret: body.app_secret, enabled: body.enabled, poll_interval_seconds: body.poll_interval_seconds,
        model_scope: body.model_scope,
      })
      if (!pendingSave.current || pendingSave.current.fingerprint !== fingerprint)
        pendingSave.current = { fingerprint, key: crypto.randomUUID() }
      const updated = await hikvisionConfigApi.save(body, pendingSave.current.key, controller.signal)
      if (!ownsResponse()) return
      pendingSave.current = null
      setConfiguration(updated)
      form.setFieldsValue({ app_key: '', app_secret: '' })
      const refreshed = await hikvisionApi.status(controller.signal)
      if (!ownsResponse()) return
      setStatus(refreshed)
      message.success('配置已保存；运行状态已独立刷新')
    } catch (reason) { if (ownsResponse()) setError(errorMessage(reason)) }
    finally { if (ownsResponse()) setSaving(false) }
  }

  async function validateCredentials() {
    const controller = new AbortController()
    validationController.current?.abort()
    validationController.current = controller
    const ownerGeneration = authGeneration
    const ownsResponse = () => !controller.signal.aborted && useSession.getState().authGeneration === ownerGeneration
    setValidating(true); setValidation(null); setError(null)
    try {
      const result = await hikvisionConfigApi.validateReadOnly(controller.signal)
      if (!ownsResponse()) return
      setValidation(result.result === 'READ_ONLY_OK' ? '只读 API 核验成功。仅读取组织信息；未注册订阅、未触达设备。' : '只读核验未完成。')
    } catch (reason) { if (ownsResponse()) setError(errorMessage(reason)) }
    finally {
      if (ownsResponse()) setValidating(false)
    }
  }


  return <Card title="海康 FireCloud 连接配置" loading={loading}>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert type="info" showIcon message="保存配置、只读认证核验和运行采集是三个独立状态。此处核验只读取组织 API，不会注册订阅或主动访问设备。" />
      {configuration && <Descriptions size="small" column={{ xs: 1, sm: 2 }} bordered>
        <Descriptions.Item label="保存状态">{configuration.configuration_source === 'ENVIRONMENT' ? '环境配置（不回显）' : `数据库配置 · revision ${configuration.revision}`}</Descriptions.Item>
        <Descriptions.Item label="保存凭据">{configuration.configured ? '已配置' : '未配置'}{configuration.app_key_masked ? ` · App Key ${configuration.app_key_masked}` : ''}</Descriptions.Item>
        <Descriptions.Item label="独立密钥存储">{configuration.secret_storage_ready ? <Tag color="green">可用</Tag> : <Tag color="red">未配置，禁止保存</Tag>}</Descriptions.Item>
        <Descriptions.Item label="最近采集">{status?.last_success_at ?? '尚无成功记录'}</Descriptions.Item>
        <Descriptions.Item label="生效运行状态">{status?.enabled ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>} {status?.configured ? '已配置凭据' : '未配置凭据'}</Descriptions.Item>
        <Descriptions.Item label="采集结果">{status?.last_error ?? '无当前错误'}</Descriptions.Item>
        <Descriptions.Item label="实际采集数量">设备 {status?.devices_total ?? '—'} · 未分配 {status?.unassigned_total ?? '—'} · 已绑定 {status?.linked_total ?? '—'}</Descriptions.Item>
        <Descriptions.Item label="生效周期">{status?.poll_interval_seconds ?? '—'} 秒</Descriptions.Item>
      </Descriptions>}
      {configuration?.configuration_source === 'ENVIRONMENT' && <Alert type="warning" showIcon message="现有环境凭据继续运行且不会回显；可在此直接保存，将已配置凭据加密迁移到数据库，无需重新输入。后续留空 Secret 保留现有密文。" />}
      {error && <Alert type="error" showIcon message={error} />}
      {validation && <Alert type="success" showIcon message={validation} />}
      <Form form={form} layout="vertical" onFinish={save} disabled={!canWrite || loading}>
        <Form.Item name="endpoint" label="HTTPS Endpoint" rules={[{ required: true, type: 'url' }]}>
          <Input placeholder={initialEndpoint} autoComplete="url" />
        </Form.Item>
        <Form.Item name="app_key" label="App Key" rules={[{ required: !configuration?.configured, message: '尚无可用凭据时必须填写 App Key' }]} extra={configuration?.app_key_masked ? `当前保存值：${configuration.app_key_masked}；留空保留现有凭据。` : '已配置的环境凭据可留空迁移；未配置时需填写。'}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="app_secret" label="App Secret（仅写入）" extra="不返回明文；首次保存请填写，后续留空保留已存密文。">
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Space align="start" wrap>
          <Form.Item name="enabled" label="启用周期采集" valuePropName="checked"><Switch /></Form.Item>
          <Form.Item name="poll_interval_seconds" label="采集周期（秒）" rules={[{ required: true }]}>
            <InputNumber min={10} max={3600} step={10} />
          </Form.Item>
          <Form.Item name="model_scope" label="型号范围" rules={[{ required: true }]}>
            <Input disabled style={{ width: 180 }} />
          </Form.Item>
        </Space>
        <Space wrap>
          <Button type="primary" htmlType="submit" loading={saving} disabled={!canWrite || !configuration?.secret_storage_ready}>保存配置</Button>
          <Button onClick={validateCredentials} loading={validating} disabled={!canWrite}>只读认证核验</Button>
        </Space>
      </Form>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        配置范围固定为首批型号 800235。保存只更新配置，不会主动访问云端；采集由运行服务按生效周期执行。endpoint 限定为厂商官方 HTTPS 地址，TLS 校验与禁止重定向保持启用。
      </Typography.Paragraph>
    </Space>
  </Card>
}
