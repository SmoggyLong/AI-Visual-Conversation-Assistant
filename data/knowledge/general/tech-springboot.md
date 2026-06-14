---
title: Spring Boot 常见错误与解决方案
type: general
keywords: Spring Boot,启动失败,端口占用,404,Bean注入,循环依赖
---

## 问题1：启动报错 org.springframework.beans.factory.UnsatisfiedDependencyException
**原因**：Bean 依赖注入失败，可能是构造器参数没有对应的 Bean。

**解决步骤**：
1. 检查报错的类是否加了 `@Component` / `@Service` / `@Configuration` 注解
2. 检查构造器参数类型是否与已注册的 Bean 匹配
3. 如果同一类型有多个 Bean，添加 `@Qualifier("beanName")` 指定
4. 检查 `@Value` 注入的属性是否在 application.yml 中配置

## 问题2：端口 8080 已被占用
**原因**：上一个进程未正常关闭或端口被其他应用占用。

**解决步骤**：
```bash
# Windows 查找占用进程
netstat -ano | findstr :8080
# 查到 PID 后结束进程
taskkill /PID <pid> /F

# 或修改 application.yml
server:
  port: 8081
```

## 问题3：@Value 注入的值为 null
**原因**：`@Value` 注在字段上时，构造器执行时属性还未注入。

**解决方案**：
- 方案A：将 `@Value` 移到构造器参数上
```java
public MyService(@Value("${my.key}") String key) {
    this.key = key;
}
```
- 方案B：使用 `@PostConstruct` 方法中读取

## 问题4：Jackson 序列化循环引用导致 StackOverflow
**原因**：双向关联的对象在序列化时无限递归。

**解决方案**：
```java
// 在属性上加注解
@JsonIgnoreProperties("parent")
private ParentEntity parent;

// 或使用 @JsonManagedReference / @JsonBackReference
```
