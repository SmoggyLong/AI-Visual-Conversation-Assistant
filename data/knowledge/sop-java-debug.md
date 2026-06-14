---
title: Java异常排查SOP
type: sop
keywords: 报错,异常,NullPointerException,NPE,OutOfMemory,504,502,崩溃,排查,日志
---

## 步骤1：确定异常类型
查看错误日志中的异常栈，确定异常类型：
- `NullPointerException`：空指针，检查对象初始化/注入
- `OutOfMemoryError`：内存溢出，检查堆内存和GC日志
- `TimeoutException`：超时，检查下游服务和网络

## 步骤2：定位根因
在日志中搜索异常发生时间点前后5秒的日志。重点关注：
- 是否有并发请求导致资源竞争
- 第三方API是否返回异常
- 数据库连接池是否耗尽

## 步骤3：常见修复方案
### NullPointerException
```java
// 问题代码
String name = user.getProfile().getName();  // profile 可能为 null

// 修复
String name = Optional.ofNullable(user.getProfile())
    .map(Profile::getName).orElse("未知");
```

### OutOfMemoryError
```bash
# 1. 导出堆转储
jmap -dump:live,file=heap.hprof <pid>
# 2. 用 MAT (Memory Analyzer) 分析大对象
# 3. 检查是否有内存泄漏（未关闭的流、静态集合不断增长）
# 4. 增加堆内存: -Xmx2g -Xms2g
```

### 超时问题
```java
// HttpClient 加超时
HttpClient client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(5))
    .build();
```

## 步骤4：验证修复
重启服务后观察日志和监控指标，确认异常不再出现。压测验证修复后的性能。
