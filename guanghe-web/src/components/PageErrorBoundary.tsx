import { ReloadOutlined } from '@ant-design/icons'
import { Button } from 'antd'
import { Component, type ReactNode } from 'react'
import { StateCard } from './GlobalState'

interface PageErrorBoundaryProps {
  children: ReactNode
  onReload?: () => void
  resetKey?: string
}

/** Catch rejected lazy imports as well as render errors; never repeat a business mutation. */
export class PageErrorBoundary extends Component<PageErrorBoundaryProps, { failed: boolean }> {
  state = { failed: false }

  static getDerivedStateFromError() {
    return { failed: true }
  }

  componentDidUpdate(previous: PageErrorBoundaryProps) {
    if (this.state.failed && previous.resetKey !== this.props.resetKey) {
      this.setState({ failed: false })
    }
  }

  render() {
    if (!this.state.failed) return this.props.children

    return <div className="state-surface" role="alert">
      <StateCard
        icon={<ReloadOutlined />}
        title="页面加载失败"
        description="页面可能已更新或加载出现异常。重新加载不会重复提交任务；已提交任务请到任务中心查看，未保存的输入可能丢失。"
        action={<Button type="primary" onClick={this.props.onReload ?? (() => window.location.reload())}>
          重新加载页面
        </Button>}
      />
    </div>
  }
}
