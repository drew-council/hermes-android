{ ... }:
{
  projectRootFile = "flake.nix";
  programs.nixfmt.enable = true;
  settings.excludes = [
    ".direnv/**"
    "keystore/**"
    "keystore.properties"
    "**/build/**"
    ".gradle/**"
  ];
}
