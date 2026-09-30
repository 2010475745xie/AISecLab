package com.xieguojian.aiseclab.core.engine

import com.xieguojian.aiseclab.core.model.Dimension
import com.xieguojian.aiseclab.core.model.TestCase

/**
 * 攻击模块插件接口。每个攻击类型实现此接口并注册进 AttackEngine。
 * 模块返回静态用例集；未来可被声明式 attack spec 描述、供社区贡献新攻击类型。
 */
interface AttackModule {
    val id: String
    val dimension: Dimension
    val displayName: String
    fun buildCases(): List<TestCase>
}
