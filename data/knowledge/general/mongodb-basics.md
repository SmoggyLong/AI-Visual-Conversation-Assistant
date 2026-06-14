# MongoDB 基础操作

本文档无 front matter，测试默认解析路径。

## 连接数据库
```javascript
// 默认连接
mongosh mongodb://localhost:27017/avca

// 查看所有数据库
show dbs
// 切换数据库
use avca
```

## 常用查询命令
```javascript
// 查询全部
db.collection.find()

// 条件查询
db.collection.find({ "field": "value" })

// 正则查询
db.collection.find({ "title": /退款/ })

// 分页
db.collection.find().skip(10).limit(10)

// 计数
db.collection.countDocuments({ "type": "SOP" })

// 聚合
db.collection.aggregate([
  { $group: { _id: "$type", count: { $sum: 1 } } }
])
```

## 索引管理
```javascript
// 创建单字段索引
db.collection.createIndex({ "title": 1 })

// 创建复合索引
db.collection.createIndex({ "type": 1, "createdAt": -1 })

// 查看索引
db.collection.getIndexes()

// 删除索引
db.collection.dropIndex("indexName")
```

## 备份与恢复
```bash
# 导出
mongodump --db avca --out /backup/

# 导入
mongorestore --db avca /backup/avca/
```
