// The screens the pseudo-locale journeys and the space measurement walk (ADR 0028): the ones a
// person reaches from the sidebar and Settings, by their web route, at a desktop and a phone width.
export const SCREENS = [
  { name: 'chat', path: '/chat' },
  { name: 'new-chat', path: '/new' },
  { name: 'search', path: '/search' },
  { name: 'agents', path: '/agents' },
  { name: 'tasks', path: '/tasks' },
  { name: 'workflows', path: '/workflows' },
  { name: 'schedules', path: '/schedules' },
  { name: 'rooms', path: '/rooms' },
  { name: 'settings-account', path: '/settings/account' },
  { name: 'settings-display', path: '/settings/display' },
  { name: 'settings-models', path: '/settings/models' },
  { name: 'settings-users', path: '/settings/users' },
  { name: 'settings-workspaces', path: '/settings/workspaces' },
  { name: 'settings-notifications', path: '/settings/notifications' },
  { name: 'settings-webhooks', path: '/settings/webhooks' },
  { name: 'settings-devices', path: '/settings/devices' },
  { name: 'settings-usage', path: '/settings/usage' },
  { name: 'settings-about', path: '/settings/about' },
] as const;

export const WIDTHS = [
  { name: 'desktop', width: 1280, height: 800 },
  { name: 'phone', width: 390, height: 844 },
] as const;
