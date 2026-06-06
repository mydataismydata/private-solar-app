# Inverter fault-code tables

Each `*.json` file here maps an inverter's numeric fault codes to human-readable text.
They're loaded at runtime, and the user picks their inverter brand on the **Discover** tab.

## Add your inverter

1. Copy `ecoworthy.json` to a new file named after your brand, e.g. `victron.json`.
2. Edit the fields:

```json
{
  "id": "victron",                       // unique, lowercase, no spaces (used internally)
  "name": "Victron",                     // shown in the brand picker
  "match": ["VICTRON", "MULTIPLUS"],     // optional: hints for future auto-detection
  "source": "where these codes came from (manual page, etc.)",
  "codes": {
    "1": "Description for fault code 1",
    "2": "Description for fault code 2"
  }
}
```

3. Only `id`, `name`, and `codes` are required. Codes are the numbers the inverter
   reports/displays; gaps (codes with no entry) are fine and show as "Unknown fault".

That's it — drop the file in, rebuild, and your brand appears in the picker. No code changes needed.
