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

宿主创建 MemberLib(JavaPlugin), 取得 MemberService 并注入业务组件。宿主的 plugin.yml 声明原有 membership 命令和权限；启用时调用 enable(DataSource, DatabaseMode), 卸载时先释放宿主的会员定义注册, 再调用 close(), 最后关闭宿主数据库。库只撤销自己注册的监听器、命令和定时任务, 不清理宿主的其他服务。

## 服务与模型

MemberService 负责定义注册、玩家缓存、异步加载和修改。MemberRepository 保留原表、字段与到期规则；MemberChangedEvent 经宿主调度器在主线程发布。MemberKey、MemberScope、MemberRecord、MemberView 是会员模型, MemberRegistration 表示一项定义的注册生命周期。

## 存储接入

宿主负责配置、连接池、驱动和数据库迁移。库接收通用 JDBC DataSource 与 DatabaseMode, 不创建或关闭连接池, 不读取或生成配置文件, 不依赖宿主插件的类型。宿主需在启用前创建 mythic_membership 和 mythic_membership_player_name 表；memberlib/db 保留原始建表迁移, 供宿主接入时使用。

## ThePit 接入

ThePit 在自己的数据库迁移中通过 V84 创建会员表, MemberLoader 在数据库初始化后启用会员库, 玩家数据加载前注册会员定义。卸载按相反顺序执行, 在 ThePit 关闭连接池前关闭会员库。会员数据与玩法数据共用 plugins/MythicThePit/database.yml 指定的数据库, 不再访问 plugins/MythicMembership。
