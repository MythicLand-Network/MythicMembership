# 架构

## 目录

- [库边界](#库边界)
- [宿主生命周期](#宿主生命周期)
- [服务与模型](#服务与模型)
- [存储接入](#存储接入)
- [ThePit 接入](#thepit-接入)

## 库边界

MythicMemberLib 是一个 Java 库, 由宿主打包并管理生命周期。代码位于 cn.mythicland.memberlib, 不划分 API 与 Bukkit 子模块, 不包含 JavaPlugin 入口、plugin.yml 或 Bukkit 服务导出。

## 宿主生命周期

宿主创建 MemberLib(JavaPlugin), 取得 MemberService 并注入业务组件。宿主的 plugin.yml 声明 member 命令和原有权限；启用时调用 enable(DataSource, DatabaseMode), 卸载时先释放宿主的会员定义注册, 再调用 close(), 最后关闭宿主数据库。库只撤销自己注册的监听器、命令和定时任务, 不清理宿主的其他服务。

## 服务与模型

MemberService 负责定义注册、玩家缓存、异步加载和修改。lookup 异步读取独立会员视图, 不写入或覆盖在线缓存, 用于宿主显示离线玩家会员信息。MemberRepository 保留原表、字段与到期规则；MemberChangedEvent 经宿主调度器在主线程发布。MemberKey、MemberScope、MemberRecord、MemberView 是会员模型, MemberRegistration 表示一项定义的注册生命周期。

activeMembers(MemberKey) 异步返回该权益的有效 MemberRecord 列表, 不覆盖在线缓存。extendCommitted(UUID, MemberKey, Duration) 将权益更新和结果读取置于同一数据库事务, 仅在提交确认后返回记录, 缓存刷新由宿主另行调用 refresh。原 extend 复用相同写入实现并保留原缓存及事件语义。MemberCommitException 表示提交结果无法确认, 宿主应核实而不盲目重复支付或退款。

## 存储接入

宿主负责配置、连接池、驱动和数据库迁移。库接收通用 JDBC DataSource 与 DatabaseMode, 不创建或关闭连接池, 不读取或生成配置文件, 不依赖宿主插件的类型。宿主需在启用前创建 mythic_membership 和 mythic_membership_player_name 表；memberlib/db 保留原始建表迁移, 供宿主接入时使用。

## ThePit 接入

ThePit 在自己的数据库迁移中通过 V84 创建会员表, MemberLoader 在数据库初始化后启用会员库, 玩家数据加载前注册会员定义。卸载按相反顺序执行, 在 ThePit 关闭连接池前关闭会员库。会员数据与玩法数据共用 plugins/MythicThePit/database.yml 指定的数据库, 不再访问 plugins/MythicMembership。
