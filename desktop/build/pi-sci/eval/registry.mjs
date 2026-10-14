import { BASE_TASKS } from "./tasks.mjs";
import { REPO_TASKS } from "./tasks-repo.mjs";
import { SCALE_TASKS } from "./tasks-scale.mjs";
import { SESSION_TASKS } from "./tasks-session.mjs";

// --suite selects one of these groups.
export const TASKS = [
	...BASE_TASKS.map((task) => ({ suite: "base", ...task })),
	...SCALE_TASKS,
	...SESSION_TASKS,
	...REPO_TASKS,
];
