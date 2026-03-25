# frontend-mobile

v3.0 Phase 1 小程序/App MVP 基础工程。

## 页面范围

- 登录
- 首页
- 项目列表
- 聊天
- 任务进度
- 个人中心
- 积分充值

## 关键实现

- 通过 `@smartark/api-sdk` 复用统一接口封装。
- 通过 `@smartark/domain` 复用统一类型。
- 自动透传 `X-Client-Platform`、`X-App-Version`、`X-Device-Id`。
- 聊天链路支持“流式优先 + 轮询兜底”基础状态收敛。
- 充值链路支持下单后自动轮询订单状态并到账刷新积分。

## 当前状态

- `npm run type-check` 已通过。
- `npm run build:h5` 已打通并通过。

## 版本锁定（可稳定构建）

- `@dcloudio/uni-app`: `3.0.0-5000420260318001`
- `@dcloudio/vite-plugin-uni`: `3.0.0-5000420260318001`
- `@dcloudio/types`: `3.4.28`
- `vite`: `5.2.8`
- `@vitejs/plugin-vue`: `5.2.x`
- `vue`: `3.4.21`
- `pinia`: `2.1.7`
