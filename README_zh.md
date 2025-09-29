# CraftAudit – 方块/实体审计与回档工具 (Forge)

## 依赖
需要 Minrcraft SQLite JDBC / Minecraft MySQL JDBC 作为前置

## 简介
CraftAudit 是一个服务器端的 Forge 审计模组，记录玩家与环境对世界的变更（方块放置/破坏、容器存取、点火、展示框与画交互、桶的使用、击杀等），提供实时查询、范围检索、回档/恢复与撤销功能，支持 SQLite 或 MySQL 存储。

## 主要特性
- **方块变更日志**
  - 玩家破坏/放置（可选记录方块状态/NBT）
  - 环境破坏（爆炸、液体替换、重力）

- **交互日志**
  - 容器存取（会话绑定，坐标精确）
  - 告示牌编辑（记录文本内容）
  - 点火（营火/蜡烛/TNT/火方块）
  - 红石交互：按钮/拉杆/门
  - 展示框/画：放入/取出/旋转/放置/破坏
  - 桶操作：装液体/倒液体/装生物/挤奶
  - 击杀：记录击杀者、受害者、原因、射弹、武器、距离等

- **查询与检索**
  - 审计模式下左/右键方块查看对应日志（分页）
  - 半径+时间范围检索（near）

- **回档/恢复与撤销**
  - 回档指定玩家的范围与时限内放置/破坏（rollback）
  - 恢复所有破坏（玩家+环境）或按类型过滤（restore）
  - 恢复击杀（kill）：原坐标复活非玩家实体（默认状态）
  - 撤销最近一次回档/恢复（undo）：恢复现场或移除生成实体

- **存储后端**
  - SQLite（默认）或 MySQL
  - 可配置数据库路径与参数

- **其他**
  - 物品/方块本地化名美化
  - 审计员自身操作不写入日志

## 指令
_所有 craftaudit 指令有 `/ca` 简写，需 OP 权限 ≥2。_

### 基础
- `/craftaudit status`  
  显示数据库/模式状态
- `/craftaudit inspect` 或 `/ca i`  
  切换审计模式（左键方块日志，右键交互日志）
- `/craftaudit log [page]`  
  查看最近右键位置的交互日志（分页）
- `/craftaudit blocklog [page]`  
  查看最近左键位置的方块日志（分页）

### 范围检索
- `/craftaudit near <radius> <time> [page]`  
  以玩家为中心，半径与时间范围内检索日志  
  时间格式：Ns/Nm/Nh/Nd（如 30m、12h、5d）

### 回档/恢复/撤销
- `/craftaudit rollback <player> <time> [radius=10]`  
  回档某玩家在范围与时间内的放置/破坏
- `/craftaudit restore <time> [radius=10] [type]`  
  恢复破坏或击杀，可按类型过滤：
  - 无 type：恢复所有破坏（玩家+环境）
  - type=break：仅玩家破坏
  - type=natural|natural_break：仅环境破坏
  - type=explosion|fluid|gravity：仅对应环境原因
  - type=kill 或 kill:<实体ID>：恢复击杀（复原非玩家实体）
- `/craftaudit undo`  
  撤销最近一次回档/恢复（恢复方块现场/移除生成实体）
- `/craftaudit purge <time>`  
  清理早于指定时间的日志（不可撤销）

### 时间格式
- 例：30s、15m、12h、7d

## 安装
- 服务器放入 Forge mods 目录，需 Java 17（1.19+）
- 首次启动后生成配置文件

## 配置
- 数据库
  - SQLite：默认路径 gameDir/craftaudit/craftaudit.db
  - MySQL：可配 host/port/database/user/password/SSL/params

## 数据与隐私
- 仅记录必要事件和坐标/物品/方块/实体ID等
- 破坏方块实体时可记录压缩 NBT（可选/有限制）
- 建议定期使用 purge 清理旧日志

## 构建
- 需 JDK 17 与 Forge MDK
- 标准流程：导入 Gradle，同步依赖，构建 Jar

## 贡献
- 欢迎提交 Issue/PR 或建议新功能

## 许可证
采用 Creative Commons Attribution 4.0 International (CC BY 4.0) 许可。  
详见 [LICENSE](LICENSE)。

版权所有 (c) 2025 CraftAudit 开发者
