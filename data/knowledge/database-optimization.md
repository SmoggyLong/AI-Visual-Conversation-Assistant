---
title: 数据库性能优化指南
type: sop
keywords: 数据库,性能,优化,索引,慢查询,MySQL,MongoDB
---

## 步骤1：定位慢查询
### MySQL
```sql
-- 开启慢查询日志
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;

-- 查看慢查询
SHOW GLOBAL STATUS LIKE 'Slow_queries';
```

### MongoDB
```javascript
// 查看慢查询
db.currentOp({ "active" : true, "secs_running" : { "$gt" : 1 } })

// 查看执行计划
db.collection.find({}).explain("executionStats")
```

## 步骤2：分析执行计划
重点关注以下指标：
- `type`：ALL（全表扫描）需要优化
- `rows`：扫描行数过大需要加索引
- `Extra`：出现 Using filesort / Using temporary 需要调整

## 步骤3：添加索引
```sql
-- MySQL 单列索引
CREATE INDEX idx_status ON orders(status);

-- MySQL 复合索引
CREATE INDEX idx_user_status ON orders(user_id, status);

-- MySQL 覆盖索引
CREATE INDEX idx_covering ON orders(user_id, status, amount);
```

```javascript
// MongoDB 索引
db.orders.createIndex({ "status": 1 })
db.orders.createIndex({ "user_id": 1, "status": 1 })
```

## 步骤4：验证优化效果
再次执行 EXPLAIN 或 explain()，确认：
- 索引被正确使用
- 扫描行数显著减少
- 查询时间降低到可接受范围

## 常见性能问题
- 索引失效：WHERE 条件使用函数或运算
- 隐式类型转换：字符串字段传数字查询
- 大表 JOIN：超过百万行考虑分步查询或异步处理
- 锁等待：长事务导致行锁/表锁
