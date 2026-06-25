# Lookatme - 微信消息监护系统

监听平板微信通知消息，同步到局域网服务器，监护人通过网页查看。

## 系统架构

平板 (APK) → 局域网 HTTP → 电脑 (Flask 服务器) → 浏览器查看

## 部署步骤

### 1. 启动服务器（电脑端）

```bash
cd server
pip install flask
python app.py
```

浏览器打开 `http://localhost:5000` 即可查看消息。

### 2. 安装 APK（平板端）

1. 从 GitHub Actions 下载 APK 文件
2. 在平板上安装 APK
3. 打开 App，输入电脑的局域网 IP 地址（如 `http://192.168.1.100:5000`）
4. 点击「授权通知监听权限」并开启
5. 保存地址即可自动运行

### 3. 查看消息

电脑浏览器打开 `http://localhost:5000` 或同局域网其他设备访问 `http://电脑IP:5000`

## 查找电脑局域网 IP

Windows: 打开命令提示符，输入 `ipconfig`，找到 IPv4 地址
