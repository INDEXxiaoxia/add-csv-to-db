# openGauss 数据目录迁移方案（生产环境）

> 目标：把 openGauss-lite 5.0.1 的数据目录从系统盘 `/var/lib/opengauss/data` 迁移到数据盘 `/home/opengauss/data`，释放系统盘空间，保证数据完整、可回滚、最小化停机风险。

---

## 0. 已锁定的事实

| 项 | 值 |
|---|---|
| 版本 | openGauss-lite 5.0.1（单机精简版） |
| GAUSSHOME（安装目录，**不动**） | `/usr/local/opengauss` |
| PGDATA（要迁的数据目录） | `/var/lib/opengauss/data` |
| 数据量 | **58G**（base 56G + pg_xlog 2.3G，其余都很小） |
| 数据库端口 | **7654** |
| OS 用户/组 | `opengauss(992) / opengauss(988)` |
| opengauss 用户 HOME | `/var/lib/opengauss`（家目录本身不动，只迁 `data` 子目录） |
| 表空间 | 只有 pg_default / pg_global，**无自定义表空间** |
| postgresql.conf 路径配置 | 没有指向 data 之外的目录，**无需改 conf** |
| 启动方式 | systemd 服务 `opengauss.service`（当前 disabled） |
| postmaster.opts | 仅 `gaussdb` 二进制路径，无 `-D` → **改 PGDATA 即生效** |
| pg_tblspc | 空目录，无外部链接 |
| 新数据目录 | `/home/opengauss/data` |
| core 文件处理 | 跟 data 一起迁过去，迁完归档到 `/home/gauss_backup/cores/` |

**预估停机时长**：rsync 58G 在本地盘 → SSD 一般 5–10 分钟，加上停启库和验证，**完整窗口建议 30–45 分钟**。

---

## 1. 迁移前最后核对（不停机就能做）

进入维护窗口前 1 小时再做一次环境快照：

```bash
# 1.1 关键路径再扫一眼
cat /usr/lib/systemd/system/opengauss.service

# 1.2 当前连接、长事务
su - opengauss -c "gsql -d postgres -p 7654 -c \"
  select pid, usename, application_name, state,
         now()-xact_start as xact_age, query
  from pg_stat_activity
  where pid <> pg_backend_pid() order by xact_age desc nulls last;\""

# 1.3 复制槽 / 逻辑订阅（lite 一般没有）
su - opengauss -c "gsql -d postgres -p 7654 -c \"select * from pg_replication_slots;\""

# 1.4 磁盘
df -h /
df -h /home
```

> 把 `opengauss.service` 的内容完整复制保存一份，后面要改它，必须先有原始版本。

---

## 2. 通知业务、进入维护窗口

到了维护窗口时间：

1. 通知应用方停止写入与读连接。
2. 关闭定时任务（ETL、报表等）。
3. 可选：临时屏蔽业务 IP（单机版一般直接停库即可）。

---

## 3. 准备新数据目录与备份目录

```bash
# root 执行
mkdir -p /home/opengauss
chown opengauss:opengauss /home/opengauss
chmod 700 /home/opengauss
ls -ld /home/opengauss
# 期望：drwx------ opengauss opengauss
```

```bash
BACKUP_DIR=/home/gauss_backup/$(date +%Y%m%d_%H%M)
mkdir -p $BACKUP_DIR
chown -R opengauss:opengauss /home/gauss_backup     # 关键，否则 opengauss 用户没写权限
echo "本次备份目录：$BACKUP_DIR"
```

---

## 4. 备份（三件套：服务文件 / 配置 / 逻辑全备）

### 4.1 备份 systemd 服务文件 + 用户环境

```bash
cp -a /usr/lib/systemd/system/opengauss.service $BACKUP_DIR/
cp -a /var/lib/opengauss/.bash_profile          $BACKUP_DIR/bash_profile.opengauss 2>/dev/null
cp -a /var/lib/opengauss/.bashrc                $BACKUP_DIR/bashrc.opengauss       2>/dev/null
```

### 4.2 备份关键配置

```bash
cp -a /var/lib/opengauss/data/postgresql.conf  $BACKUP_DIR/
cp -a /var/lib/opengauss/data/pg_hba.conf      $BACKUP_DIR/
cp -a /var/lib/opengauss/data/pg_ident.conf    $BACKUP_DIR/ 2>/dev/null
cp -a /var/lib/opengauss/data/postmaster.opts  $BACKUP_DIR/
```

### 4.3 逻辑全备（最重要的兜底）

> 数据库仍在线时做，58G 估计 20–60 分钟。**这步可以提前在维护窗口外做**，缩短停机时间。

切到 opengauss 用户（**本机走 unix socket + peer 认证，不需要密码**）：

```bash
su - opengauss
gs_dumpall -U opengauss -p 7654 -f $BACKUP_DIR/MPPDB_backup.sql
exit
```

> 注意几点：
> - **不要加 `-h 127.0.0.1`**：openGauss 5.0 对初始管理员 sysadmin 强制密码，走 TCP 时即使 pg_hba 写 trust 也不放过；走本地 unix socket 则直接 peer 认证通过。
> - **不要用 `su - opengauss -c "..."` 包一层**：那样密码提示符会被吞掉，看起来像"卡死"。必须先 `su - opengauss` 进交互 shell 再跑。
> - `-W` 在 openGauss 是**带值参数**（要写密码值），不是 PostgreSQL 那种无参开关。不指定就走默认认证。

校验：

```bash
ls -lh $BACKUP_DIR/MPPDB_backup.sql
tail -3 $BACKUP_DIR/MPPDB_backup.sql
# 末尾应有：-- PostgreSQL database cluster dump complete
```

#### 跑的时候怎么确认它没卡死

**另开一个 ssh 终端**：

```bash
# 文件大小持续增长 = 在备份
watch -n 5 "ls -lh /home/gauss_backup/*/MPPDB_backup.sql"

# 进程在跑
ps -ef | grep gs_dumpall | grep -v grep
```

### 4.4 记录对账数据（迁完做对比）

```bash
su - opengauss -c "gsql -d postgres -p 7654 -c \"
  select datname, pg_database_size(datname) as bytes
  from pg_database order by datname;\"" | tee $BACKUP_DIR/db_sizes_before.txt

# 如有关键业务表，记下行数：
# su - opengauss -c "gsql -d <库> -p 7654 -c \"select count(*) from <关键表>;\"" >> $BACKUP_DIR/rowcount_before.txt
```

---

## 5. 停止数据库

### 5.1 先 checkpoint

```bash
su - opengauss -c "gsql -d postgres -p 7654 -c 'CHECKPOINT;'"
```

### 5.2 优雅停库（fast 模式）

> **环境差异提醒**：本台机器实际是手工 `gs_ctl start` 启动的（systemd 服务一直是 disabled），systemd **没有真正接管进程**。因此本次停库**不能用 `systemctl stop`（停不下来），必须用 `gs_ctl stop`**。第 7.2 节修复 systemd 服务文件之后，下次起停才会被 systemd 正常接管。

```bash
# 本次（systemd 未接管）：必须用 gs_ctl
su - opengauss -c "gs_ctl stop -D \$PGDATA -m fast"

# 验证 systemd 是否接管的方法：
# systemctl status opengauss
# 如果 Active: inactive (dead) 但 ps 里看到 gaussdb 还在跑 → systemd 没接管，必须用 gs_ctl
# 如果 Active: active (running) → 才能用 systemctl stop
```

### 5.3 确认完全停了

```bash
ps -ef | grep -E 'gaussdb|gs_' | grep -v grep
# 必须为空

cat /var/lib/opengauss/data/gaussdb.state
# 期望看到 Normal shutdown / Stopped

ls /var/lib/opengauss/data/postmaster.pid 2>/dev/null
# 正常停库后此文件自动删除
```

> 如果 `fast` 30 秒停不下来，再用 `-m immediate`。**不要 kill -9 主进程**。

---

## 6. 迁移数据

### 6.1 rsync 整体复制（root 执行）

```bash
time rsync -aHX --numeric-ids --info=progress2 \
     /var/lib/opengauss/data/   /home/opengauss/data/
```

注意末尾两个 `/` 都不能省。

### 6.2 校验

```bash
# 总大小（应几乎一致，差异不超过几个 KB）
du -sb /var/lib/opengauss/data/    /home/opengauss/data/

# 文件数
echo "src: $(find /var/lib/opengauss/data/    -type f | wc -l)"
echo "dst: $(find /home/opengauss/data/ -type f | wc -l)"

# 关键文件 md5（必须完全一致）
md5sum /var/lib/opengauss/data/global/pg_control \
       /home/opengauss/data/global/pg_control
md5sum /var/lib/opengauss/data/PG_VERSION \
       /home/opengauss/data/PG_VERSION

# 权限属主
ls -ld /home/opengauss/data
# 必须：drwx------ opengauss opengauss
stat -c '%U %G %a' /home/opengauss/data/global/pg_control
# 必须：opengauss opengauss 600
```

任何一项校验对不上 **不要继续**。

### 6.3 把旧目录改名（**回滚锚点，不要删**）

```bash
mv /var/lib/opengauss/data  /var/lib/opengauss/data.bak_$(date +%Y%m%d_%H%M)
ls -ld /var/lib/opengauss/data.bak_*
```

---

## 7. 修改配置，让数据库认新目录

### 7.1 改 opengauss 用户环境变量

```bash
grep -n PGDATA /var/lib/opengauss/.bash_profile /var/lib/opengauss/.bashrc 2>/dev/null

# 备份后替换
sed -i.bak_$(date +%s) 's|/var/lib/opengauss/data|/home/opengauss/data|g' \
    /var/lib/opengauss/.bash_profile
[ -f /var/lib/opengauss/.bashrc ] && grep -q '/var/lib/opengauss/data' /var/lib/opengauss/.bashrc && \
  sed -i.bak_$(date +%s) 's|/var/lib/opengauss/data|/home/opengauss/data|g' /var/lib/opengauss/.bashrc

# 验证
su - opengauss -c 'echo $PGDATA'
# 期望：/home/opengauss/data
```

### 7.2 改 systemd 服务文件

> 服务文件里通常有 3 处 `gs_ctl ... -D /var/lib/opengauss/data` 需要改（ExecStart / ExecStop / ExecReload）。`WorkingDirectory=/var/lib/opengauss` **不要动**——这是 opengauss 用户的 HOME，本次不迁。
> 改完这一步意义很大：以后 `systemctl start/stop/reload opengauss` 都能正常工作，systemd 才能"正式接管"实例。

```bash
# 先看现状
grep -nE 'PGDATA|/var/lib/opengauss' /usr/lib/systemd/system/opengauss.service

# 备份
cp /usr/lib/systemd/system/opengauss.service $BACKUP_DIR/opengauss.service.before_edit

# sed 只替换 /var/lib/opengauss/data，不会动 WorkingDirectory 那行
sed -i 's|/var/lib/opengauss/data|/home/opengauss/data|g' \
    /usr/lib/systemd/system/opengauss.service

# 看 diff（必须恰好 3 处改动）
diff $BACKUP_DIR/opengauss.service.before_edit /usr/lib/systemd/system/opengauss.service

systemctl daemon-reload
```

### 7.3 检查遗漏的引用

```bash
grep -rn '/var/lib/opengauss/data' /etc/ /usr/lib/systemd/ 2>/dev/null
# 期望：除了 .bak 备份文件，无其他命中
```

---

## 8. 启动并验证

### 8.1 启动

> 第 7.2 节修好 systemd 服务文件之后，**本次启动用 `systemctl start` 是正确的选择**，借机让 systemd 正式接管。
> 如果担心 systemd 还有问题，也可以先用 `gs_ctl start` 验证数据本身没问题，再单独排查 systemd。

**首选（让 systemd 正式接管）：**

```bash
systemctl start opengauss
sleep 5
systemctl status opengauss --no-pager
```

启动失败的话：

```bash
journalctl -u opengauss --no-pager -n 100
tail -200 /home/opengauss/data/pg_log/postgresql-*.log
```

**备用（如果 systemd 启不起来，先用 gs_ctl 把库起来恢复业务，systemd 后续再修）：**

```bash
su - opengauss -c 'gs_ctl start -D $PGDATA'
```

### 8.2 进程与状态

```bash
ps -ef | grep gaussdb | grep -v grep

su - opengauss -c 'cat $PGDATA/gaussdb.state'
# 期望 db_state: Normal

ss -lntp | grep 7654
```

### 8.3 数据完整性核对

```bash
su - opengauss -c "gsql -d postgres -p 7654 -c \"
  select datname, pg_database_size(datname) as bytes
  from pg_database order by datname;\"" | tee $BACKUP_DIR/db_sizes_after.txt

diff $BACKUP_DIR/db_sizes_before.txt $BACKUP_DIR/db_sizes_after.txt
# 期望基本一致（极小统计差异可忽略）
```

如果提前记录了关键业务表行数，逐个比对。

### 8.4 业务侧冒烟

- 应用配置不需要改（IP/端口未变），让应用尝试连接
- 跑 1–2 个核心读写事务
- 看 `pg_log` 日志：
  ```bash
  tail -200 /home/opengauss/data/pg_log/postgresql-*.log
  ```

---

## 9. 业务恢复 + 磁盘检查

```bash
df -h /
df -h /home
```

`/` 应该掉到 30%+ 以下，`/home` 多出 58G 占用。让业务恢复正常流量。

---

## 10. 观察期（至少 24–72 小时）

```bash
# 实时日志
tail -f /home/opengauss/data/pg_log/postgresql-*.log

# 连接情况
su - opengauss -c "gsql -d postgres -p 7654 -c \"
  select state, count(*) from pg_stat_activity group by state;\""

# 磁盘
df -h /home
```

观察期内**绝对不要删旧目录**。

---

## 11. 最终清理（观察期通过后）

```bash
# 11.1 再次确认正常
systemctl status opengauss --no-pager
su - opengauss -c 'cat $PGDATA/gaussdb.state'

# 11.2 归档 core 文件（事后由 DBA 排查崩溃原因）
mkdir -p /home/gauss_backup/cores
mv /home/opengauss/data/core-gaussdb-*.lz4 /home/gauss_backup/cores/ 2>/dev/null
chown -R opengauss:opengauss /home/gauss_backup/cores

# 11.3 删除旧目录
du -sh /var/lib/opengauss/data.bak_*
rm -rf /var/lib/opengauss/data.bak_*

# 11.4 备份归档到外部存储（NAS / 对象存储）
ls -lh /home/gauss_backup/

# 11.5 强烈建议：把 systemd 服务设为开机自启
# 迁移前本机 systemd 服务是 disabled 状态，数据库靠人工 gs_ctl 起，重启不会自动恢复。
# 借这次迁移修复掉这个隐患：
systemctl enable opengauss
systemctl is-enabled opengauss
# 期望输出：enabled
```

---

## 12. 回滚预案

只要还没执行第 11 步，旧目录都还在，可以回滚：

```bash
# 1) 停掉新实例
systemctl stop opengauss

# 2) 恢复 systemd 服务文件
cp $BACKUP_DIR/opengauss.service /usr/lib/systemd/system/opengauss.service
systemctl daemon-reload

# 3) 恢复 opengauss 用户环境
cp $BACKUP_DIR/bash_profile.opengauss /var/lib/opengauss/.bash_profile

# 4) 旧目录改回原名
mv /var/lib/opengauss/data.bak_*  /var/lib/opengauss/data

# 5) 启动
systemctl start opengauss
systemctl status opengauss --no-pager
su - opengauss -c 'cat $PGDATA/gaussdb.state'
```

最坏情况（新旧目录都坏），用 `$BACKUP_DIR/MPPDB_backup.sql` 在新初始化的实例里恢复：

```bash
# 仅作示意，执行前请联系 DBA
# su - opengauss -c "gsql -d postgres -p 7654 -f $BACKUP_DIR/MPPDB_backup.sql"
```

---

## 13. 注意事项与坑位记录

1. **`pkg_5.0.1` 和 `recode_install_flag`** 不要动，是安装记录，跟运行数据无关，留在 `/var/lib/opengauss/` 即可。
2. **opengauss 用户 HOME 仍是 `/var/lib/opengauss`**，本方案不改 HOME，避免 passwd / ssh key / bash 历史等配套问题。
3. **socket 文件**默认在 `/tmp`，迁移不影响应用连接。
4. **alarm_component=`/opt/snas/bin/snas_cm_cmd`** lite 版一般没装，是个告警钩子，不影响迁移。
5. **后续防止再次写爆 `/`**：监控里给 `/` 加 80% 阈值告警；`pg_log` / `pg_audit` 已随 data 一起迁走。
6. **`/home` 是 LVM**（`/dev/mapper/openeuler-home`），后续可在线扩容：
   ```bash
   lvextend -L +200G /dev/mapper/openeuler-home
   xfs_growfs /home          # 或 resize2fs，看文件系统类型
   ```

### 启停坑：systemd 服务文件存在但没接管进程

如果迁移前实例是手工 `gs_ctl start` 启的，systemd 单元会显示 `inactive (dead)`、进程却在跑很久。这种状态下：

- `systemctl stop` **完全不会动**那个 gaussdb 进程，必须用 `gs_ctl stop -D $PGDATA -m fast`
- `systemctl status` 看到的"Active 时间"是误导信息，要以 `ps -ef | grep gaussdb` 为准
- 第 7.2 节修好 service 文件 + `daemon-reload` 之后，systemd 才能正常起停

### rsync 坑

- **必须 root 跑 rsync**。opengauss 用户跑会读不到 root 创建的临时文件（比如调试 trust 时 `sed -i.bak` 留下的 `pg_hba.conf.bak_*`），报 Permission denied。
- 第一次 rsync 跑得早、数据库还在跑 → pg_log、pg_xlog、postmaster.pid 边复制边变，文件会"小不一样"。**正确做法是停库后再 rsync 一次（加 `--delete`）**。
- `du -sb` 在不同文件系统上对目录 inode 的字节数可能不同，**少量的 KB 差异是文件系统记账差异，不是数据差异**。判断数据一致性看：文件数 + `pg_control` 的 md5 + `PG_VERSION` 的 md5 + `rsync --delete` 是否再传文件（0 传输 = 一致）。
- data 目录下出现 `pg_hba.conf.bak_*` 这种 .bak 文件，那是 sed 调试遗留，**直接 `rm` 掉**，不要迁过去。

### 备份时遇到过的坑（避坑清单）

- `gs_dumpall -W` 不带值会把后面的 `-f` 当密码 → 报 "too many command-line arguments"。**不要随便加 `-W`**。
- `su - opengauss -c "gs_dumpall ..."` 看起来"卡死"实际上是密码提示符被吞 → 必须先 `su - opengauss` 进交互 shell 再跑。
- 默认不带 `-h` 时走 unix socket，可能报 `could not connect to server: No such file or directory` → 加 `-p 7654` 或直接 `gsql` 测一下能不能连。
- openGauss 5.0 对**初始管理员（sysadmin）走 TCP 连接强制密码**，即使 pg_hba 写 trust 也不放过；走本地 unix socket 则 peer 认证直接通过。**所以备份命令不要加 `-h 127.0.0.1`**。
- 业务账号（如 `gbsm`）**不能用 `gs_dumpall`**，必须超管。业务账号只能用 `gs_dump` 备份单个库，但会丢失角色/权限/表空间等全局对象，作为整库迁移的备份不完整。
- `/home/gauss_backup` 必须 `chown opengauss:opengauss`，否则 opengauss 用户写不进去，`2>` 重定向也会 Permission denied（`sudo cmd 2> file` 的重定向是当前 shell 做的，sudo 救不了）。

---

## 14. 一句话总结

1. 备份：`su - opengauss` → `gs_dumpall -U opengauss -p 7654 -f /home/gauss_backup/.../MPPDB_backup.sql`（不加 `-h`）
2. 停库：先看 `systemctl status`；如 systemd 没接管，用 `su - opengauss -c "gs_ctl stop -D \$PGDATA -m fast"`
3. 迁数据（root）：`rsync -aHX --numeric-ids --delete /var/lib/opengauss/data/ /home/opengauss/data/`
4. 校验：文件数一致 + `pg_control` md5 一致 + `PG_VERSION` md5 一致
5. 旧目录改名留底：`mv /var/lib/opengauss/data /var/lib/opengauss/data.bak_xxx`
6. 改 PGDATA：`/var/lib/opengauss/.bash_profile`（PGDATA 行）+ `/usr/lib/systemd/system/opengauss.service`（3 处 `-D` 路径）
7. 重载 + 启库：`systemctl daemon-reload && systemctl start opengauss`
8. 验证 → 业务恢复 → 观察 72 小时 → 删旧目录 → `systemctl enable opengauss`
