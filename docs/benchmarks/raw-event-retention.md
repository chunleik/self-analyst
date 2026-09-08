# 原始事件永久保留基准

启动完整性检查由 `events.raw.integrity.startupScope` 控制，默认 `latest` 检查最新分区，`all` 检查全部
分区。下述历史写入与查询基准不包含新增启动检查耗时；评估多月历史的启动成本时，应单独记录范围、
分区数、数据大小及耗时，不能把写入吞吐直接当作启动检查性能。

## 2026-09-03 单月等价基准

使用 `RawEventStoreBenchmarkTest` 在本地 Windows/JDK 21 环境执行 30 天、每 5 秒一次 heartbeat 的等价负载：

- 原始事件数：518,400
- 批量写入大小：10,000
- 写入吞吐：约 29,498 events/s
- SQLite 分区大小：273,620,992 bytes
- 查询页大小：1,000
- 查询页数：519
- 分页查询 p95：约 311.794 ms
- 查询后事件数：518,400，未发生自动删除

该结果用于开发期容量回归，不代表不同磁盘、杀毒软件或文件系统上的性能承诺。日常测试默认跳过该基准；使用以下命令显式运行：

```powershell
mvn -pl self-analyst-events -am '-Dtest=RawEventStoreBenchmarkTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Draw.benchmark=true' test
```
