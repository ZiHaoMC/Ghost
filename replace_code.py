import os
import re

# ================= 配置区域 =================

# 输入包含代码的文本文件路径
# 根据你的描述，文件似乎在隔壁目录。如果在当前目录，请改为 'custom_output.txt'
INPUT_FILE_PATH = '../python-project/custom_output.txt'

# 项目源码的基础路径 (根据你的 tree 命令输出确定)
# 文本中的路径 (如 features/ghostblock/...) 将会被拼接到这个路径后面
PROJECT_BASE_PATH = 'src/main/java/com/zihaomc/ghost'

# ===========================================

def parse_and_replace():
    # 1. 检查输入文件是否存在
    if not os.path.exists(INPUT_FILE_PATH):
        # 尝试检查当前目录是否存在同名文件作为备选
        local_path = 'custom_output.txt'
        if os.path.exists(local_path):
            print(f"⚠️  在 '{INPUT_FILE_PATH}' 未找到文件，但在当前目录找到了 '{local_path}'，将使用该文件。")
            target_input_path = local_path
        else:
            print(f"❌ 错误: 找不到输入文件: {INPUT_FILE_PATH}")
            print("请确认文件路径正确，或将包含代码的文本保存为 custom_output.txt 放在脚本同级目录。")
            return
    else:
        target_input_path = INPUT_FILE_PATH

    print(f"📖 正在读取: {target_input_path}")
    
    with open(target_input_path, 'r', encoding='utf-8') as f:
        lines = f.readlines()

    current_rel_path = None
    is_capturing_code = False
    code_buffer = []
    
    # 简单的状态机解析
    for line in lines:
        strip_line = line.strip()

        # [状态1] 寻找文件名: **path/to/File.java**
        # 正则匹配被 ** 包裹的路径
        file_match = re.match(r'^\*\*(.+?)\*\*\s*$', strip_line)
        if file_match:
            current_rel_path = file_match.group(1).strip()
            is_capturing_code = False 
            # print(f"🔍 发现目标文件标记: {current_rel_path}")
            continue

        # [状态2] 寻找代码块开始: ```java
        if strip_line.startswith('```java') and current_rel_path:
            is_capturing_code = True
            code_buffer = [] # 清空缓冲区，准备记录新内容
            continue

        # [状态3] 寻找代码块结束: ```
        if strip_line == '```' and is_capturing_code:
            is_capturing_code = False
            # 写入文件
            write_file(current_rel_path, code_buffer)
            current_rel_path = None # 重置，等待下一个文件
            continue

        # [状态4] 捕获代码内容
        if is_capturing_code:
            code_buffer.append(line)

def write_file(rel_path, content_lines):
    """将内容写入到实际的项目路径中"""
    
    # 拼接完整路径: src/main/... + features/ghostblock/...
    full_path = os.path.join(PROJECT_BASE_PATH, rel_path)
    
    # 获取目录路径并确保其存在（防止新文件目录不存在报错）
    dir_path = os.path.dirname(full_path)
    if not os.path.exists(dir_path):
        try:
            os.makedirs(dir_path)
            print(f"📁 创建目录: {dir_path}")
        except OSError as e:
            print(f"❌ 创建目录失败: {e}")
            return

    # 写入文件
    try:
        with open(full_path, 'w', encoding='utf-8') as f:
            f.writelines(content_lines)
        print(f"✅ 已替换/写入: {full_path}")
    except Exception as e:
        print(f"❌ 写入失败 {full_path}: {e}")

if __name__ == "__main__":
    print("🚀 开始自动替换代码...")
    print(f"📂 项目根目录: {os.path.abspath(PROJECT_BASE_PATH)}")
    
    if not os.path.exists(PROJECT_BASE_PATH):
        print("❌ 警告: 项目源码目录不存在，请确保你在 Ghost 项目根目录下运行此脚本。")
    else:
        parse_and_replace()
        print("🏁 处理完成。")
