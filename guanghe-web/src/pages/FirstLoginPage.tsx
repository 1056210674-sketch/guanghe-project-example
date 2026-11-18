import { Alert, Button, Checkbox, Form, Input, Typography } from 'antd'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import type { CompleteFirstLoginRequest } from '../api/authApi'
import { isApiError } from '../api/apiError'
import {
  clearFirstLoginActivation,
  finishFirstLogin,
  readFirstLoginActivation,
  safeAgreementUrl,
} from '../auth'
import { Brand } from '../components/Brand'
import { TraceId } from '../components/TraceId'
import { APP_PATHS } from '../routes/paths'
import { postLoginTarget } from '../routes/routePolicy'
import '../styles/login.css'

interface FirstLoginFormValues {
  newPassword: string
  confirmPassword: string
  termsAccepted: boolean
  privacyAccepted: boolean
}

export function FirstLoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const context = useMemo(() => readFirstLoginActivation(), [])
  const requestedTarget =
    (location.state as { from?: string } | null)?.from ?? context?.returnTarget
  const submitLock = useRef(false)
  const [pending, setPending] = useState(false)
  const [submitError, setSubmitError] = useState<unknown>()
  const termsUrl = context ? safeAgreementUrl(context.terms.url) : undefined
  const privacyUrl = context ? safeAgreementUrl(context.privacy.url) : undefined

  useEffect(() => {
    if (!context) {
      navigate(APP_PATHS.login, {
        replace: true,
        state: { reason: 'activation-expired' },
      })
    }
  }, [context, navigate])

  const submit = async (values: FirstLoginFormValues) => {
    if (!context || submitLock.current) return
    submitLock.current = true
    setPending(true)
    setSubmitError(undefined)
    try {
      const request: CompleteFirstLoginRequest = {
        activationToken: context.activationToken,
        newPassword: values.newPassword,
        termsAccepted: true,
        privacyAccepted: true,
        termsVersion: context.terms.version,
        privacyVersion: context.privacy.version,
      }
      const user = await finishFirstLogin(request)
      navigate(postLoginTarget(user.role, requestedTarget), { replace: true })
    } catch (error) {
      if (isApiError(error) && [401, 403, 409].includes(error.status)) {
        clearFirstLoginActivation()
        navigate(APP_PATHS.login, {
          replace: true,
          state: {
            reason: 'activation-expired',
            message: error.message,
            traceId: error.traceId,
          },
        })
        return
      }
      setSubmitError(error)
    } finally {
      submitLock.current = false
      setPending(false)
    }
  }

  if (!context) return null

  const apiError = isApiError(submitError) ? submitError : undefined

  return (
    <main className="first-login-page">
      <section className="first-login-card" aria-labelledby="first-login-title">
        <Brand />
        <div>
          <Typography.Title id="first-login-title" level={2}>完成首次登录</Typography.Title>
          <Typography.Text type="secondary">
            {context.user.displayName?.trim() || context.user.phoneMasked || '新用户'}，请设置新密码并确认协议。
          </Typography.Text>
        </div>
        {submitError ? (
          <Alert
            type="error"
            showIcon
            title={apiError?.message ?? '网络连接失败，请检查网络后重试。'}
            description={<TraceId traceId={apiError?.traceId} />}
          />
        ) : null}
        <Form<FirstLoginFormValues> layout="vertical" requiredMark={false} onFinish={submit}>
          <Form.Item label="新密码" name="newPassword" rules={[
            { required: true, message: '请输入新密码' },
            { min: 6, max: 128, message: '密码长度须为6–128个字符' },
          ]}>
            <Input.Password autoComplete="new-password" maxLength={128} />
          </Form.Item>
          <Form.Item label="确认新密码" name="confirmPassword" dependencies={['newPassword']} rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                return value === getFieldValue('newPassword')
                  ? Promise.resolve()
                  : Promise.reject(new Error('两次输入的密码不一致'))
              },
            }),
          ]}>
            <Input.Password autoComplete="new-password" maxLength={128} />
          </Form.Item>
          <Form.Item name="termsAccepted" valuePropName="checked" rules={[
            { validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error('请阅读并同意用户协议')) },
          ]}>
            <Checkbox disabled={!termsUrl}>
              我已阅读并同意 <a href={termsUrl} target="_blank" rel="noopener noreferrer">《用户协议》</a>
            </Checkbox>
          </Form.Item>
          <Form.Item name="privacyAccepted" valuePropName="checked" rules={[
            { validator: (_, value) => value ? Promise.resolve() : Promise.reject(new Error('请阅读并同意隐私政策')) },
          ]}>
            <Checkbox disabled={!privacyUrl}>
              我已阅读并同意 <a href={privacyUrl} target="_blank" rel="noopener noreferrer">《隐私政策》</a>
            </Checkbox>
          </Form.Item>
          {(!termsUrl || !privacyUrl) ? (
            <Alert type="error" showIcon title="协议地址无效，请联系管理员后重试。" />
          ) : null}
          <Button type="primary" htmlType="submit" size="large" block loading={pending} disabled={pending || !termsUrl || !privacyUrl}>
            完成设置并登录
          </Button>
        </Form>
      </section>
    </main>
  )
}
