import { useState, useRef, useCallback, useEffect } from 'react';
import type {
  Message,
  ClientMessageType,
  ClientPayload,
  ClientPayloadMap,
  ServerPayload,
  ConnectionState,
} from '../types/messages';

/** 服务端 WebSocket 端点地址 */
const WS_URL = 'ws://localhost:8080/ws/conversation';

/** 断线重连间隔（毫秒） */
const RECONNECT_DELAY = 3000;

/** 最大重连次数 */
const MAX_RECONNECT_ATTEMPTS = 5;

/** 心跳间隔（毫秒），每 30 秒发一次 PING */
const PING_INTERVAL = 30000;

interface UseWebSocketReturn {
  /** WebSocket 连接状态 */
  connectionState: ConnectionState;
  /** 服务端分配的业务会话 ID */
  sessionId: string | null;
  /** 发送消息（类型安全） */
  sendMessage: <T extends ClientMessageType>(
    type: T,
    payload: ClientPayloadMap[T]
  ) => void;
  /** 最新收到的服务端消息 */
  lastServerMessage: Message<ServerPayload> | null;
  /** 注册消息监听回调，返回取消注册的函数 */
  onMessage: (handler: (msg: Message<ServerPayload>) => void) => () => void;
  /** 手动连接 */
  connect: () => void;
  /** 手动断开 */
  disconnect: () => void;
}

/**
 * WebSocket 连接管理 Hook。
 *
 * 负责：
 * - WebSocket 连接建立/断开/重连（最多 5 次，间隔 3 秒）
 * - 自动心跳（30 秒 PING）
 * - 消息收发（JSON 序列化/反序列化）
 * - 类型安全的 sendMessage（泛型约束 payload 类型）
 *
 * 组件挂载时自动连接，卸载时自动断开。
 *
 * @returns WebSocket 连接状态与操作方法
 */
export function useWebSocket(): UseWebSocketReturn {
  /** 连接状态 */
  const [connectionState, setConnectionState] = useState<ConnectionState>('disconnected');

  /** 服务端分配的业务会话 ID */
  const [sessionId, setSessionId] = useState<string | null>(null);

  /** 最新收到的服务端消息 */
  const [lastServerMessage, setLastServerMessage] = useState<Message<ServerPayload> | null>(null);

  const wsRef = useRef<WebSocket | null>(null);
  const reconnectAttemptsRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout>>();
  const pingTimerRef = useRef<ReturnType<typeof setInterval>>();

  /** 消息监听器集合（支持多组件同时监听） */
  const messageHandlersRef = useRef<Set<(msg: Message<ServerPayload>) => void>>(new Set());

  /** 是否为主动断开（主动断开时不再重连） */
  const intentionalCloseRef = useRef(false);

  /**
   * 清理定时器。
   */
  const cleanup = useCallback(() => {
    if (pingTimerRef.current) {
      clearInterval(pingTimerRef.current);
      pingTimerRef.current = undefined;
    }
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = undefined;
    }
  }, []);

  /**
   * 建立 WebSocket 连接。
   * 连接成功后发送 CONNECTION_INIT 消息并启动心跳。
   * 连接断开时自动重连（最多 5 次）。
   */
  const connect = useCallback(() => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return;
    intentionalCloseRef.current = false;

    setConnectionState('connecting');
    const ws = new WebSocket(WS_URL);

    ws.onopen = () => {
      setConnectionState('connected');
      reconnectAttemptsRef.current = 0;
      wsRef.current = ws;

      // 发送连接初始化消息，携带设备信息
      ws.send(JSON.stringify({
        type: 'CONNECTION_INIT',
        timestamp: Date.now(),
        sessionId: '',
        payload: {
          deviceInfo: {
            userAgent: navigator.userAgent,
            platform: navigator.platform,
            screenWidth: window.screen.width,
            screenHeight: window.screen.height,
          },
        },
      }));

      // 启动心跳定时器，每 30 秒发送 PING
      pingTimerRef.current = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({
            type: 'PING',
            timestamp: Date.now(),
            sessionId: sessionId ?? '',
            payload: null,
          }));
        }
      }, PING_INTERVAL);
    };

    ws.onmessage = (event) => {
      try {
        const msg: Message<ServerPayload> = JSON.parse(event.data);

        if (msg.type === 'CONNECTION_ACK') {
          setSessionId((msg.payload as { sessionId: string }).sessionId);
        }

        setLastServerMessage(msg);
        // 通知所有已注册的消息监听器
        messageHandlersRef.current.forEach((handler) => handler(msg));
      } catch {
        // 忽略 JSON 解析失败的消息
      }
    };

    ws.onclose = () => {
      setConnectionState('disconnected');
      cleanup();

      if (!intentionalCloseRef.current && reconnectAttemptsRef.current < MAX_RECONNECT_ATTEMPTS) {
        setConnectionState('reconnecting');
        reconnectTimerRef.current = setTimeout(() => {
          reconnectAttemptsRef.current += 1;
          connect();
        }, RECONNECT_DELAY);
      }
    };

    ws.onerror = () => {
      setConnectionState('error');
    };
  }, [sessionId, cleanup]);

  /**
   * 主动断开 WebSocket 连接（不再重连）。
   */
  const disconnect = useCallback(() => {
    intentionalCloseRef.current = true;
    cleanup();
    reconnectAttemptsRef.current = MAX_RECONNECT_ATTEMPTS;
    if (wsRef.current) {
      wsRef.current.close(1000, 'user disconnect');
      wsRef.current = null;
    }
    setConnectionState('disconnected');
    setSessionId(null);
  }, [cleanup]);

  /**
   * 发送消息到服务端。
   *
   * @param type    消息类型（类型安全的枚举值）
   * @param payload 消息载荷（类型由 ClientPayloadMap 约束）
   */
  const sendMessage = useCallback(
    <T extends ClientMessageType>(
      type: T,
      payload: ClientPayloadMap[T]
    ) => {
      if (wsRef.current?.readyState === WebSocket.OPEN) {
        const msg: Message<ClientPayload> = {
          type,
          timestamp: Date.now(),
          sessionId: sessionId ?? '',
          payload: payload as ClientPayload,
        };
        wsRef.current.send(JSON.stringify(msg));
      }
    },
    [sessionId]
  );

  /**
   * 注册消息监听回调。
   *
   * @param handler 收到服务端消息时的回调
   * @returns 取消注册的函数（调用后该 handler 不再收到消息）
   */
  const onMessage = useCallback(
    (handler: (msg: Message<ServerPayload>) => void) => {
      messageHandlersRef.current.add(handler);
      return () => {
        messageHandlersRef.current.delete(handler);
      };
    },
    []
  );

  /** 组件挂载时自动连接，卸载时自动断开 */
  useEffect(() => {
    connect();
    return () => {
      disconnect();
    };
  }, []);

  return {
    connectionState,
    sessionId,
    sendMessage,
    lastServerMessage,
    onMessage,
    connect,
    disconnect,
  };
}
