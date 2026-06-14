---
title: 项目README说明
type: general
keywords: 项目,说明,架构,技术栈
---

## 项目简介
AI 视觉对话助手是一个集成摄像头和麦克风的智能对话系统。

## 技术栈
- 前端：React 18 + TypeScript + Tailwind CSS
- 后端：Spring Boot 3.3.1 + Java 17
- LLM：LangChain4j 0.36.2（DeepSeek + 智谱）
- Vision：智谱 GLM-4V
- STT：百度语音识别
- 存储：MongoDB + Redis
- Embedding：Ollama nomic-embed-text

## 核心流程
用户说话 → VAD检测 → 百度STT转文字 → 同时摄像头截图 → GLM-4V分析画面 → IntentRecognizer判断意图 → Agent路由 → LLM生成回复 → 前端展示

## 支持的Agent
- VisionAgent：画面描述与对话
- KnowledgeAgent：知识技术问答
- ConversationAgent：日常闲聊
- GameAgent：成语接龙
