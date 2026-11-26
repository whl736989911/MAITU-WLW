import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntApplication, ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { MotionConfig } from 'motion/react'
import App from './App'
import { RenderFailureBoundary } from './components/render-failure-boundary'
import 'antd/dist/reset.css'
import './index.css'
import './workspace-theme.css'
import './components/dashboard-charts.css'
import './legacy-customer.css'

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <RenderFailureBoundary>
    <ConfigProvider locale={zhCN} theme={{ token: { colorPrimary: '#1f6feb', colorError: '#c7463c', colorWarning: '#b7791f', borderRadius: 8, fontSize: 14, controlHeight: 38, fontFamily: '"PingFang SC", "Microsoft YaHei", system-ui, sans-serif' }, components: { Table: { cellPaddingBlock: 10, cellPaddingInline: 12 }, Drawer: { paddingLG: 20 } } }}>
      <AntApplication><MotionConfig reducedMotion="user"><App/></MotionConfig></AntApplication>
    </ConfigProvider>
    </RenderFailureBoundary>
  </React.StrictMode>,
)
