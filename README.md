# Ghost

> 你说得对，Ghost 是为 Minecraft 1.8.9 提供 辅助/实用 功能的 Mod ~~顺便给我练习下Java~~。它体积小巧（至少目前是的），主要侧重于*幽灵方块*的使用。你可以配置多种幽灵方块的使用方法，达到普通玩家无法达到的地方（请确认服务器的反作弊允许你这么干）。还有其他更多的可选功能（如自动在方块边缘按shift），大部分注释和部分代码由AI编写。
>
> **注意：** 该 Mod 可能会破坏游戏平衡性，如果您在多人游戏中使用，请获取管理员的同意。

## ✨ 功能预览

- [x] 👻 幽灵方块
- [x] 🔧 更多幽灵方块配置
- [x] 📜 Shift+鼠标滚轮 切换聊天历史
- [x] 💾 可选 一直自动保存/一直使用批量放置
- [x] ⌨️ 可选 能使用 ↩ 键直接输入上次的命令
- [x] 🚶 可选 自动在方块边缘按shift
- [x] 👁️ 可选 玩家透视
- [x] 🏹 射在玩家身上的箭隐形

## 📝 路线图 (Roadmap)

- [ ] 🧪 假药水效果
- [ ] 🗒️ 快速做笔记


## 👨‍💻 开发者指南

### 如何构建此项目？

(Linux)

1.  **克隆仓库**
    首先，使用此命令在一个合适的目录把项目克隆下来：
    ```bash
    git clone https://github.com/ZiHaoMC/Ghost.git
    ```

2.  **进入项目目录**
    其次，使用`cd`命令进入刚刚克隆的项目：
    ```bash
    cd Ghost
    ```

3.  **构建项目**
    再来，如果你修改好了你自己的版本，使用这个命令构建项目（未修改就是最后一个版本）：
    ```bash
    ./gradlew build
    ```
    或者跳过单元测试（二选一）：
    ```bash
    ./gradlew build -x test
    ```
    
## ⚖️ 开源协议 (License)

本项目采用多协议授权：

*   **核心框架及原创功能**：[MIT License](LICENSE) (Copyright © 2025 ZiHaoMC)
*   **AutoMine & Pathfinding 模块**：衍生自 [Baritone](https://github.com/cabaletta/baritone)，遵循 **GNU LGPL v3.0** 协议。
*   **BuildGuess 词库**：数据来源于 [GTB-Solver](https://github.com/oycyc/GTB-Solver)，遵循 **MIT License**。

详细协议文本请查阅项目根目录下的 `LICENSE` 文件。
    