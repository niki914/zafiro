# 诊断与使用情况

[English](TELEMETRY.md) | 中文

Zafiro 收集匿名诊断与性能数据，用于监控稳定性、异常与模型可用率。

## 数据收集范围

所有收集项均在开源白名单契约 [`telemetry-contract/contract.yaml`](../telemetry-contract/contract.yaml) 中静态定义：

* **环境与硬件**：系统版本、ABI、设备型号、内存档位
* **应用生命周期**：冷启动计数与启动类型
* **可用率指标**：模型调用成功与错误大类、前台使用频次

## 隐私边界

* 不收集个人身份信息
* 不收集对话内容、Prompt 或模型回复
* 不收集 API Key、Token 或认证凭据
* 不读取本地文件或设备敏感标识

## 源码实现

* 契约定义：[`telemetry-contract/contract.yaml`](../telemetry-contract/contract.yaml)
* 客户端实现：[`business/remote/`](../business/remote/src/main/kotlin/com/niki914/zafiro/business/remote/)
