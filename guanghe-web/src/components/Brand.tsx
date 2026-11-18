import logoUrl from '../assets/brand/company-logo-ai-hd-white.png'

interface BrandProps {
  admin?: boolean
  compact?: boolean
}

export function Brand({ admin = false, compact = false }: BrandProps) {
  return (
    <div className={`brand ${compact ? 'brand--compact' : ''}`}>
      <span className="brand__logo-frame">
        <img className="brand__logo" src={logoUrl} alt="光禾AI智能体框架" />
      </span>
      <span className="brand__name" title={admin ? '光禾AI智能体框架运营后台' : '光禾AI智能体框架'}>
        {admin ? '光禾AI智能体框架运营后台' : '光禾AI智能体框架'}
      </span>
    </div>
  )
}
