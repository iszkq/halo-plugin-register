import { defineComponent, h } from "vue";

export default defineComponent({
  name: "InviteMenuIcon",
  render() {
    return h(
      "svg",
      {
        viewBox: "0 0 24 24",
        fill: "none",
        stroke: "currentColor",
        "stroke-width": "1.8",
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
        "aria-hidden": "true",
      },
      [
        h("path", {
          d: "M4.75 8.25A2.25 2.25 0 0 1 7 6h10a2.25 2.25 0 0 1 2.25 2.25v1.15a2.1 2.1 0 0 0 0 4.2v1.15A2.25 2.25 0 0 1 17 17H7a2.25 2.25 0 0 1-2.25-2.25V13.6a2.1 2.1 0 0 0 0-4.2Z",
        }),
        h("path", { d: "M9 6v11" }),
        h("path", { d: "M12.5 10h3.5" }),
        h("path", { d: "M12.5 13.5h2.25" }),
      ],
    );
  },
});
