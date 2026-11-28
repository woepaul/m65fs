# bash completion for the m65 tools (m65fs @VERSION@). TAB completes card paths (m65:/PATH, and
# plain paths in the tools that only use the card) when the MEGA65's transfer helper is
# already running and free; the MEGA65 is never taken over for it.
# Load it with: eval "$(m65 completion bash)", or install it: m65 completion install
_m65_complete() {
  local cmd=${COMP_WORDS[0]} out line kind first=1 icase=off
  [[ $cmd == "~/"* ]] && cmd=$HOME/${cmd#"~/"}
  [[ $(bind -v 2>/dev/null) == *"completion-ignore-case on"* ]] && icase=on
  out=$("$cmd" __complete "${COMP_LINE:0:COMP_POINT}" "$COMP_WORDBREAKS" "$icase" 2>/dev/null) || return 0
  COMPREPLY=()
  while IFS= read -r line; do
    if ((first)); then kind=$line; first=0; else COMPREPLY+=("$line"); fi
  done <<< "$out"
  case $kind in
    files) compopt -o default ;;
    dirs) compopt -o dirnames ;;
    words) [[ ${#COMPREPLY[@]} -eq 1 && ${COMPREPLY[0]} == */ ]] && compopt -o nospace ;;
  esac
  return 0
}
complete -F _m65_complete m65 @COMMANDS@
