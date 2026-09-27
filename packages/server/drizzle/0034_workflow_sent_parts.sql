CREATE TABLE `workflow_sent_parts` (
	`id` text(26) PRIMARY KEY NOT NULL,
	`owner_id` text(26) NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL,
	`workspace` text(26) NOT NULL,
	`run_key` text(26) NOT NULL,
	`node_key` text(64) NOT NULL,
	`target` text(120) NOT NULL,
	`part` integer NOT NULL,
	`message_id` text(64) NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX `workflow_sent_parts_uq` ON `workflow_sent_parts` (`run_key`,`node_key`,`target`,`part`);