# FlowDAM

**FlowDAM** 是一款基于 Root 权限的 Android 流量转发工具，专门解决 Charles/Fiddler 抓包时全系统流量混杂、难以过滤的痛点。

---

## 📱 功能预览

|                         应用列表选择                         |                           转发配置                           |                    提纯后的 Charles 列表                     |
| :----------------------------------------------------------: | :----------------------------------------------------------: | :----------------------------------------------------------: |
| <img width="1080" height="2340" alt="Image" src="https://github.com/user-attachments/assets/95ef3036-7e81-4d75-ac1f-aaacf65aeaec" /> | <img width="1080" height="2340" alt="Image" src="https://github.com/user-attachments/assets/8917a35c-e697-4863-a9bf-5dd0973b8d22" /> | <img width="1937" height="1031" alt="Image" src="https://github.com/user-attachments/assets/45dd26e9-e00f-48fd-a7b1-d93fccc453e8" /> |

---

## ✨ 核心功能

* **UID 级流量过滤**：支持按应用勾选，仅转发选中应用的流量至抓包工具，保持 Charles 列表纯净。
* **透明转发**：基于 `iptables REDIRECT` 模式，无需设置系统 WiFi 代理，对检测 VPN 的应用更友好。
* **内置内核**：集成 `sing-box` 强力驱动。

## 🛠️ 技术原理

1. 通过 `iptables -t nat -A OUTPUT -m owner --uid-owner [UID]` 匹配特定应用的流量。
2. 将匹配到的流量重定向到本地 `12345` 端口（由 **sing-box** 监听）。
3. **sing-box** 根据配置将流量作为 HTTP/Socks5 代理转发给 PC 端的 Charles。

## 🚀 快速上手

1. **放置内核**：默认支持 `arm64-v8a`。如需其他架构，请自行将对应版本的 `sing-box` 放置在 `app/src/main/assets/bin/arm64-v8a/` 中。
2. **证书安装**：将 Charles 证书推送到 Android 系统证书目录（Root 权限）。
3. **Charles 设置**：在 `Proxy` -> `Proxy Settings` 中勾选 **Enable SOCKS proxy**，设置端口。
4. **启动工具**：在 App 中输入 Charles 所在电脑的 IP 和端口，勾选目标 App，点击 **START**。

---

## ⚖️ 开源协议

* 采用 **[Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)** 协议。鼓励开发者在遵循协议的基础上，自由地使用、修改或集成本项目。
* 内核 **sing-box** 遵循其原有的 **GPL-3.0** 协议。FlowDAM 仅作为管理工具与其进行进程间调用，并不包含、修改其源代码，亦不属于其衍生作品。

> **免责声明**：本工具仅用于开发调试，禁止用于任何非法用途。