import {
  AppstoreOutlined,
  ArrowRightOutlined,
  ProfileOutlined,
  TeamOutlined,
} from '@ant-design/icons'
import { Button, Card, Typography } from 'antd'

export interface BusinessDataOverviewProps {
  onOpenBusinessSpaces: () => void
  onOpenProducts: () => void
  onOpenCompetitors: () => void
}

export function BusinessDataOverview({
  onOpenBusinessSpaces,
  onOpenProducts,
  onOpenCompetitors,
}: BusinessDataOverviewProps) {
  return (
    <section className="business-overview" aria-labelledby="business-overview-title">
      <header className="business-page-heading">
        <div>
          <Typography.Title id="business-overview-title" level={1}>
            经营资料
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            维护可复用的经营上下文，为后续商品创作与监测能力提供准确资料。
          </Typography.Paragraph>
        </div>
      </header>

      <div className="business-overview__grid">
        <Card className="business-entry-card" hoverable>
          <div className="business-entry-card__icon" aria-hidden="true">
            <ProfileOutlined />
          </div>
          <div className="business-entry-card__content">
            <Typography.Title level={3}>经营空间</Typography.Title>
            <Typography.Paragraph type="secondary">
              统一维护品牌经营背景，为商品创作与GEO监测提供准确上下文。
            </Typography.Paragraph>
          </div>
          <Button
            type="link"
            icon={<ArrowRightOutlined />}
            iconPosition="end"
            onClick={onOpenBusinessSpaces}
          >
            进入管理
          </Button>
        </Card>

        <Card className="business-entry-card" hoverable>
          <div className="business-entry-card__icon" aria-hidden="true">
            <AppstoreOutlined />
          </div>
          <div className="business-entry-card__content">
            <Typography.Title level={3}>商品</Typography.Title>
            <Typography.Paragraph type="secondary">
              按经营空间维护商品基础资料、别名与创作上下文。
            </Typography.Paragraph>
          </div>
          <Button
            type="link"
            icon={<ArrowRightOutlined />}
            iconPosition="end"
            onClick={onOpenProducts}
          >
            进入管理
          </Button>
        </Card>

        <Card className="business-entry-card" hoverable>
          <div className="business-entry-card__icon" aria-hidden="true">
            <TeamOutlined />
          </div>
          <div className="business-entry-card__content">
            <Typography.Title level={3}>竞品</Typography.Title>
            <Typography.Paragraph type="secondary">
              维护竞品名称、别名及其所属经营空间关系。
            </Typography.Paragraph>
          </div>
          <Button
            type="link"
            icon={<ArrowRightOutlined />}
            iconPosition="end"
            onClick={onOpenCompetitors}
          >
            进入管理
          </Button>
        </Card>
      </div>
    </section>
  )
}
