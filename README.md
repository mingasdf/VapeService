# Vape 4.21 Experimental Service

单进程 Java 实验服务，提供旧版 Online REST API 和 Zeus TCP 核心链路。
数据保存在本地 JSON 文件，不依赖数据库或 Redis。

## Requirements

- JDK 17

## Run

```powershell
.\gradlew.bat run
```

默认监听：

- HTTP: `127.0.0.1:8080`
- Zeus: `127.0.0.1:8091`
- 数据文件: `data/vape-service.json`

可通过环境变量覆盖：

```powershell
$env:VAPE_BIND_ADDRESS='127.0.0.1'
$env:VAPE_HTTP_PORT='8080'
$env:VAPE_ZEUS_PORT='8091'
$env:VAPE_DATA_FILE='data/vape-service.json'
.\gradlew.bat run
```

客户端启动前设置：

```powershell
$env:VAPE_ONLINE_BASE_URL='http://127.0.0.1:8080'
$env:VAPE_ZEUS_ADDRESS='127.0.0.1:8091'
```

当前恢复桥的 `gat()` 返回实验 token `0`。服务首次启动会自动创建对应账户。

## Implemented

- App Auth challenge、浏览器确认和状态轮询
- `/authenticated` 和实验注册
- Global/Online settings 读写
- Private user/profile 数据读写和 UUID 预留
- 基础 Public Profile CRUD、列表和标签接口
- Zeus protocol 10 握手
- token、Minecraft UUID/名称认证
- 单账户连接替换
- 好友关系、好友请求、上下线状态、可见性和私聊
- Party 创建、邀请、接受/拒绝、离开、删除、踢出、转让队长和选项
- Party 群聊以及 Friends/Group Ping
- Minecraft Profile 和服务器地址同步
- 位置确认、Activity 用户订阅和世界切换
- Activity snapshot、手持槽位、背包快照/增量更新和 CPS 转发
- 显示名称和心跳
- 本地 JSON 原子写入
- Health 和基础管理查询

Zeus 认证态客户端入站 ID `0-31` 均有处理分支。好友、请求和 Party 状态写入
本地 JSON；Ping、位置、背包、CPS 和 Activity 等高频状态仅在在线连接间转发。

## Test

```powershell
.\gradlew.bat test
```
