import { useEffect } from "react";
import { useLocation } from "react-router-dom";

export default function NavigationEffects() {
  const { pathname } = useLocation();
  useEffect(() => {
    const workspace = document.querySelector("[data-workspace-scroll]");
    if (workspace) workspace.scrollTop = 0;
    else window.scrollTo({ top: 0, behavior: "instant" });
    const heading = document.querySelector("main h1");
    if (heading) {
      heading.setAttribute("tabindex", "-1");
      heading.focus({ preventScroll: true });
    }
  }, [pathname]);
  return null;
}
