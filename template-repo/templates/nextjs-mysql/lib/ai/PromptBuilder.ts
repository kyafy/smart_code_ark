import 'server-only'

export class PromptBuilder {
  private readonly template: string
  private readonly variables = new Map<string, string>()

  constructor(template: string) {
    this.template = template
  }

  set(key: string, value: string | null | undefined) {
    this.variables.set(key, value ?? '')
    return this
  }

  build() {
    let result = this.template
    for (const [key, value] of this.variables.entries()) {
      result = result.replaceAll(`{{${key}}}`, value)
    }
    return result
  }
}
