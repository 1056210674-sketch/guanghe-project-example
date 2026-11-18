import { Alert, Button, Form, Input, Typography } from 'antd'
import { useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { LoginRequest } from '../api/authApi'
import { isApiError } from '../api/apiError'
import homeKitchenUrl from '../assets/login/home-kitchen.webp'
import officeDeskUrl from '../assets/login/office-desk.webp'
import outdoorCampingUrl from '../assets/login/outdoor-camping.webp'
import productMainUrl from '../assets/login/product-main.webp'
import { setFirstLoginReturnTarget, signIn } from '../auth'
import { Brand } from '../components/Brand'
import { TraceId } from '../components/TraceId'
import { APP_PATHS } from '../routes/paths'
import { postLoginTarget } from '../routes/routePolicy'
import '../styles/login.css'

interface LoginLocationState {
  from?: string
  reason?: 'expired' | 'activation-expired'
  message?: string
  traceId?: string
}

interface LoginArtworkImageProps {
  className: string
  src: string
  width: number
  height: number
  priority?: boolean
}

function LoginArtworkImage({
  className,
  src,
  width,
  height,
  priority = false,
}: LoginArtworkImageProps) {
  const [failed, setFailed] = useState(false)

  if (failed) {
    return <span className={`${className} login-art-image--fallback`} />
  }

  return (
    <img
      className={className}
      src={src}
      alt=""
      width={width}
      height={height}
      loading="eager"
      decoding="async"
      fetchPriority={priority ? 'high' : 'auto'}
      onError={() => setFailed(true)}
    />
  )
}

function loginErrorMessage(error: unknown): string {
  if (!isApiError(error)) {
    return '网络连接失败，请检查网络后重试。'
  }

  if (error.status === 429) {
    return '登录尝试过多，请稍后重试。'
  }

  if (error.status === 503) {
    return '认证服务暂时不可用，请稍后重试。'
  }

  return error.message
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const locationState = location.state as LoginLocationState | null
  const submitLock = useRef(false)
  const [loginPending, setLoginPending] = useState(false)
  const [loginError, setLoginError] = useState<unknown>()
  const submit = async (request: LoginRequest) => {
    if (submitLock.current) return
    submitLock.current = true
    setLoginPending(true)
    setLoginError(undefined)
    try {
      const result = await signIn(request)
      if (result.kind === 'activation') {
        const returnTarget = postLoginTarget(
          result.context.user.role,
          locationState?.from,
        )
        setFirstLoginReturnTarget(returnTarget)
        navigate(APP_PATHS.firstLogin, {
          replace: true,
          state: { from: returnTarget },
        })
        return
      }
      const user = result.user
      navigate(postLoginTarget(user.role, locationState?.from), { replace: true })
    } catch (error) {
      setLoginError(error)
    } finally {
      submitLock.current = false
      setLoginPending(false)
    }
  }

  const apiError = isApiError(loginError)
    ? loginError
    : undefined

  return (
    <main className="login-page">
      <section className="login-hero" aria-label="光禾AI智能体框架产品介绍">
        <div className="login-hero__inner">
          <div className="login-hero__intro">
            <Brand />
            <Typography.Title level={2}>
              让每一次创作，都更懂你的商品
            </Typography.Title>
          </div>

          <div className="login-hero__visual" aria-hidden="true">
            <div className="login-product-panel">
              <div className="login-product-panel__heading">
                <strong>商品图生成</strong>
                <span>生成多风格商品图，提升转化</span>
              </div>
              <div className="login-product-panel__gallery">
                <LoginArtworkImage
                  className="login-product-panel__image"
                  src={homeKitchenUrl}
                  width={640}
                  height={480}
                />
                <LoginArtworkImage
                  className="login-product-panel__image"
                  src={officeDeskUrl}
                  width={640}
                  height={480}
                />
                <LoginArtworkImage
                  className="login-product-panel__image"
                  src={outdoorCampingUrl}
                  width={640}
                  height={480}
                />
              </div>
            </div>

            <div className="login-product-focus">
              <LoginArtworkImage
                className="login-product-focus__image"
                src={productMainUrl}
                width={960}
                height={960}
                priority
              />
            </div>

            <div className="login-geo-card">
              <div className="login-geo-card__title">
                <span className="login-geo-card__mark" />
                <strong>GEO 品牌趋势</strong>
              </div>
              <span className="login-geo-card__period">近 7 天趋势示意</span>
              <svg
                className="login-geo-card__chart"
                viewBox="0 0 240 54"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <path
                  className="login-geo-card__chart-area"
                  d="M2 45 C28 39 43 31 67 33 C91 35 103 18 128 22 C154 26 165 14 188 17 C209 19 222 10 238 8 L238 54 L2 54 Z"
                />
                <path
                  className="login-geo-card__chart-line"
                  d="M2 45 C28 39 43 31 67 33 C91 35 103 18 128 22 C154 26 165 14 188 17 C209 19 222 10 238 8"
                />
                <circle className="login-geo-card__chart-point" cx="238" cy="8" r="3.5" />
              </svg>
              <span className="login-geo-card__caption">持续观察</span>
            </div>

            <div className="login-generation-card">
              <strong>AI 生图结果</strong>
              <div className="login-generation-card__gallery">
                <LoginArtworkImage
                  className="login-generation-card__image"
                  src={homeKitchenUrl}
                  width={640}
                  height={480}
                />
                <LoginArtworkImage
                  className="login-generation-card__image"
                  src={officeDeskUrl}
                  width={640}
                  height={480}
                />
                <LoginArtworkImage
                  className="login-generation-card__image"
                  src={outdoorCampingUrl}
                  width={640}
                  height={480}
                />
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="login-form-area" aria-label="账号密码登录">
        <div className="login-card">
          <Typography.Title level={2}>欢迎使用光禾AI智能体框架</Typography.Title>
          <div className="login-card__tab">账号密码登录</div>

          {locationState?.reason ? (
            <Alert
              className="login-card__alert"
              type="warning"
              showIcon
              message={locationState.reason === 'activation-expired' ? '首次登录会话已失效' : '登录状态已过期'}
              description={locationState.message ?? (locationState.reason === 'activation-expired'
                ? '请使用手机号或用户名和临时密码重新登录。'
                : '为保障账号安全，请重新登录。')}
            />
          ) : null}
          {locationState?.traceId ? <TraceId traceId={locationState.traceId} /> : null}

          {loginError ? (
            <Alert
              className="login-card__alert"
              type="error"
              showIcon
              message={loginErrorMessage(loginError)}
              description={<TraceId traceId={apiError?.traceId} />}
            />
          ) : null}

          <Form<LoginRequest>
            className="login-form"
            layout="vertical"
            requiredMark={false}
            onFinish={submit}
          >
            <Form.Item
              label="手机号/用户名"
              name="username"
              rules={[
                { required: true, message: '请输入手机号或用户名' },
                { max: 64, message: '账号不能超过64个字符' },
              ]}
            >
              <Input
                size="large"
                placeholder="请输入手机号或用户名"
                autoComplete="username"
                maxLength={64}
                autoFocus
              />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[
                { required: true, message: '请输入密码' },
                { max: 128, message: '密码不能超过128个字符' },
              ]}
            >
              <Input.Password
                size="large"
                placeholder="请输入密码"
                autoComplete="current-password"
                maxLength={128}
              />
            </Form.Item>
            <Button
              className="login-form__submit"
              type="primary"
              htmlType="submit"
              size="large"
              loading={loginPending}
              disabled={loginPending}
              block
            >
              登录
            </Button>
          </Form>
        </div>
      </section>
    </main>
  )
}
