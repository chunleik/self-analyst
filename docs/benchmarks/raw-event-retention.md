# 原始事件永久保留基准

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
mvn -pl self-analyst-aw -am '-Dtest=RawEventStoreBenchmarkTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Draw.benchmark=true' test
```
