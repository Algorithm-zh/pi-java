package com.piagent.domain.git.model;

/**
 * 工作区文件相对检查点的变化类型。
 */
public enum GitChangeKind {

    /** 本轮新建的文件。 */
    ADDED,

    /** 本轮修改的文件。 */
    MODIFIED,

    /** 本轮删除的文件。 */
    DELETED
}
