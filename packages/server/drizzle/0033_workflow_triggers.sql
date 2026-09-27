CREATE TABLE `workflow_trigger_deliveries` (
	`id` text(26) PRIMARY KEY NOT NULL,
	`owner_id` text(26) NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL,
	`workspace` text(26) NOT NULL,
	`trigger_id` text(26) NOT NULL,
	`workflow_id` text(26) NOT NULL,
	`status` text NOT NULL,
	`event` text(200),
	`event_id` text(200),
	`task_id` text(200),
	`workflow_run_id` text(26),
	`filtered` integer DEFAULT false NOT NULL,
	`test` integer DEFAULT false NOT NULL,
	`error` text,
	`body_preview` text,
	FOREIGN KEY (`trigger_id`) REFERENCES `workflow_triggers`(`id`) ON UPDATE no action ON DELETE cascade,
	CONSTRAINT "workflow_trigger_deliveries_status_check" CHECK("workflow_trigger_deliveries"."status" in ('received', 'duplicate', 'signature_rejected', 'filtered_out', 'run_started', 'run_succeeded', 'run_failed'))
);
--> statement-breakpoint
CREATE INDEX `workflow_trigger_deliveries_trigger_idx` ON `workflow_trigger_deliveries` (`trigger_id`,`created_at`);--> statement-breakpoint
CREATE INDEX `workflow_trigger_deliveries_run_idx` ON `workflow_trigger_deliveries` (`workflow_run_id`);--> statement-breakpoint
CREATE TABLE `workflow_trigger_seen` (
	`id` text(26) PRIMARY KEY NOT NULL,
	`owner_id` text(26) NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL,
	`workspace` text(26) NOT NULL,
	`trigger_id` text(26) NOT NULL,
	`key` text(200) NOT NULL,
	FOREIGN KEY (`trigger_id`) REFERENCES `workflow_triggers`(`id`) ON UPDATE no action ON DELETE cascade
);
--> statement-breakpoint
CREATE UNIQUE INDEX `workflow_trigger_seen_key_uq` ON `workflow_trigger_seen` (`trigger_id`,`key`);--> statement-breakpoint
CREATE INDEX `workflow_trigger_seen_created_idx` ON `workflow_trigger_seen` (`created_at`);--> statement-breakpoint
CREATE TABLE `workflow_triggers` (
	`id` text(26) PRIMARY KEY NOT NULL,
	`owner_id` text(26) NOT NULL,
	`created_at` integer NOT NULL,
	`updated_at` integer NOT NULL,
	`workspace` text(26) NOT NULL,
	`workflow_id` text(26) NOT NULL,
	`name` text(120) NOT NULL,
	`preset` text NOT NULL,
	`enabled` integer DEFAULT true NOT NULL,
	`events` text DEFAULT '[]' NOT NULL,
	`secret_ciphertext` text,
	`secret_nonce` text,
	`secret_key_id` text(64),
	`signature_header` text(120),
	`signature_encoding` text,
	`signature_prefix` text(40),
	`last_delivery_at` integer,
	FOREIGN KEY (`workflow_id`) REFERENCES `workflows`(`id`) ON UPDATE no action ON DELETE cascade,
	CONSTRAINT "workflow_triggers_preset_check" CHECK("workflow_triggers"."preset" in ('clickup', 'github', 'generic_hmac', 'token'))
);
--> statement-breakpoint
CREATE INDEX `workflow_triggers_workflow_idx` ON `workflow_triggers` (`workflow_id`);--> statement-breakpoint
ALTER TABLE `workflow_runs` ADD `workflow_trigger_id` text(26);--> statement-breakpoint
ALTER TABLE `workflow_runs` ADD `event_id` text(200);--> statement-breakpoint
ALTER TABLE `workflow_runs` ADD `task_id` text(200);--> statement-breakpoint
CREATE INDEX `workflow_runs_event_idx` ON `workflow_runs` (`workflow_id`,`event_id`);--> statement-breakpoint
CREATE INDEX `workflow_runs_task_idx` ON `workflow_runs` (`workflow_id`,`task_id`);