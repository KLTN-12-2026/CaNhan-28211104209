import { NavLink } from "react-router-dom";

export default function Sidebar({ items, title }) {
  return (
    <aside className="w-full shrink-0 border-b border-slate-200 bg-slate-900 text-slate-200 lg:min-h-full lg:w-64 lg:border-b-0 lg:border-r lg:border-slate-800">
      <div className="px-4 py-4">
        <p className="text-xs font-semibold uppercase tracking-wider text-teal-300">
          {title}
        </p>
      </div>
      <nav className="flex gap-1 overflow-x-auto px-3 pb-3 lg:flex-col lg:overflow-visible lg:pb-6">
        {items.map((item) => {
          const Icon = item.icon;
          return (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end}
              className={({ isActive }) =>
                `flex min-w-max items-center gap-2 rounded-lg px-3 py-2 text-sm font-medium transition ${
                  isActive
                    ? "bg-teal-600 text-white"
                    : "text-slate-300 hover:bg-slate-800 hover:text-white"
                }`
              }
            >
              {Icon ? <Icon className="h-4 w-4" /> : null}
              {item.label}
            </NavLink>
          );
        })}
      </nav>
    </aside>
  );
}
