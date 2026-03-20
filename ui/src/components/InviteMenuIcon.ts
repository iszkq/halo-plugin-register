import { defineComponent, h } from "vue";

export default defineComponent({
  name: "InviteMenuIcon",
  render() {
    return h(
      "svg",
      {
        width: "18",
        height: "18",
        viewBox: "0 0 24 24",
        fill: "none",
        stroke: "currentColor",
        "stroke-width": "2.2",
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
        "aria-hidden": "true",
        style: {
          display: "block",
          width: "18px",
          height: "18px",
          flexShrink: "0",
          overflow: "visible",
        },
      },
      [
        h("path", {
          d: "M5 7.5A2.5 2.5 0 0 1 7.5 5h9A2.5 2.5 0 0 1 19 7.5v1.2a2.2 2.2 0 0 0 0 4.6v1.2a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 14.5v-1.2a2.2 2.2 0 0 0 0-4.6Z",
        }),
        h("path", { d: "M9.5 5v14" }),
        h("path", { d: "M12.5 10.25h3.25" }),
        h("path", { d: "M12.5 13.75h2.25" }),
      ],
    );
  },
});
