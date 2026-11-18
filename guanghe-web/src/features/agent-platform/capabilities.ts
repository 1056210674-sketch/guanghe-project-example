/** Presentation policy only. Java still authorizes every submitted action. */
export function hasAgentCapability(capabilities: readonly string[] | undefined, capability: string): boolean {
  return capabilities?.includes(capability) === true
}

export function supportsPersonalCreativeAssets(capabilities: readonly string[] | undefined): boolean {
  return hasAgentCapability(capabilities, 'image.generate') || hasAgentCapability(capabilities, 'video.generate')
}
