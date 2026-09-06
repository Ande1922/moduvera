# 已知局限：不可控边界的 trace 断裂

设备 MQTT 上行、第三方回调等不可控边界无法携带 `traceparent`，会在平台入口开新 trace。跨断裂的桥接靠**领域键**：指令-回执用 `command_id` 配对、消息用 `message_id`、第三方回调在回调 URL / state 参数中带回平台标识。任何自定义关联字段方案在这些边界同样断裂（设备不会替你回传任何 id），故不为此引入额外字段。

设备场景见 [DEVICE.md](../scenes/DEVICE.md)。不引入自定义关联字段见 [NO-CUSTOM-CORRELATION.md](NO-CUSTOM-CORRELATION.md)。
