import type { AgentDefinition, AgentInstallation } from '../../api/agentPlatformApi'

/** Resolve this installed version, never borrow a newer catalogue entry's status or capabilities. */
export function findInstalledAgentDefinition(
  installation: AgentInstallation | undefined,
  definitions: readonly AgentDefinition[] | undefined,
): AgentDefinition | undefined {
  if (!installation) return undefined
  return definitions?.find((definition) => definition.agentKey === installation.agentKey
    && definition.version === installation.agentVersion)
}

/** Withdrawal prevents new installs, not use of an already installed version. Java remains authoritative. */
export function isInstalledAgentVersionAvailable(definition: AgentDefinition | undefined): boolean {
  return definition?.status === 'PUBLISHED' || definition?.status === 'WITHDRAWN'
}

/** UI availability only; Java remains the execution authority. Historical conversation identities are separate. */
export function isAgentInstallationAvailable(
  installation: AgentInstallation | undefined,
  definitions: readonly AgentDefinition[] | undefined,
): boolean {
  return installation?.enabled === true
    && isInstalledAgentVersionAvailable(findInstalledAgentDefinition(installation, definitions))
}
