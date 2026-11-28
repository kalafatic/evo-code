package eu.kalafatic.dcode.core.model;

/**
 * Configurable agent roles. Roles are routing/config categories, not hardcoded orchestration
 * logic: the harness resolves an agent definition per role; new roles can be added without
 * changing the engine.
 */
public enum AgentRole {
    /** Main coding agent executing implementation work. */
    MAIN,

    /** Planning agent producing task decomposition and plans. */
    PLANNER,

    /** Review agent producing code review findings. */
    REVIEWER,

    /** Architecture analysis agent. */
    ARCHITECT,

    /** Test design/verification agent. */
    TESTER,

    /** Independent evaluation agent: assesses progress/completion against the goal. */
    EVALUATOR,

    /** Documentation agent. */
    DOCUMENTER,

    /** Read-only repository analysis agent. */
    ANALYZER,

    /** User-defined role. */
    CUSTOM
}
