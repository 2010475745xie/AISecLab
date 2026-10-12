"""模块 B —— 算力共享。

与模块 A（AISecLab，`app/` + `backend/`）**互不 import**，是两个独立模块。
唯一联动方式是标准协议：本模块对外暴露一个 **OpenAI 兼容的 base_url + key**，
模块 A 把它当成「一个普通被测模型端点」填进 CONFIG 表单即可。

设计草案见 `docs/模块B-算力共享-设计草案.md`；本包只实现其中的一期。
"""

__version__ = "0.1.0"
