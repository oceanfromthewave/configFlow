/** The configured HTTP/SOCKS proxy. `url` is null when no proxy is set. */
export interface ProxySettings {
  url: string | null
  bypass: string
}

/** PUT body for saving the proxy. */
export interface SaveProxyPayload {
  url: string
  bypass?: string
}
