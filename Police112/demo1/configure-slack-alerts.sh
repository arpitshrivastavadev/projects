#!/usr/bin/env bash
  set -euo pipefail

  project_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  compose_file="$project_dir/docker-compose.yml"
  contact_file="$project_dir/infra/grafana/provisioning/alerting/contact-points.yaml"
  env_file="$project_dir/.env"

  read -r -s -p "New Slack Incoming Webhook URL for #112-alerts: " webhook
  echo

  [[ "$webhook" =~ ^https://hooks\.slack\.com/services/ ]] || {
    echo "Invalid Slack webhook URL." >&2
    exit 1
  }

  grep -qxF '.env' "$project_dir/.gitignore" || printf '\n.env\n' >> "$project_dir/.gitignore"

  umask 077
  touch "$env_file"
  chmod 600 "$env_file"

  if grep -q '^SLACK_WEBHOOK_URL=' "$env_file"; then
    sed -i 's|^SLACK_WEBHOOK_URL=.*|SLACK_WEBHOOK_URL='"$webhook"'|' "$env_file"
  else
    printf 'SLACK_WEBHOOK_URL=%s\n' "$webhook" >> "$env_file"
  fi

  grep -q '^[[:space:]]*SLACK_WEBHOOK_URL:' "$compose_file" ||
    sed -i '/GF_ALERTING_ENABLED: "false"/a\      SLACK_WEBHOOK_URL: ${SLACK_WEBHOOK_URL}' "$compose_file"

  sed -i 's|^[[:space:]]*url:.*|          url: ${SLACK_WEBHOOK_URL}|' "$contact_file"

  cd "$project_dir"
  docker compose up -d --force-recreate grafana
  echo "Done. Test it in Grafana → Alerting → Contact points → Slack Police112."
