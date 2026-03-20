import { definePlugin } from "@halo-dev/console-shared";
import { markRaw } from "vue";
import InviteMenuIcon from "./components/InviteMenuIcon";
import InviteCodesView from "./views/InviteCodesView.vue";
import "./style.css";

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: "ToolsRoot",
      route: {
        path: "invite-register",
        name: "InviteRegister",
        component: InviteCodesView,
        meta: {
          title: "邀请码注册管理",
          searchable: true,
          menu: {
            name: "邀请码管理",
            icon: markRaw(InviteMenuIcon),
            priority: 90,
          },
        },
      },
    },
  ],
  ucRoutes: [],
  extensionPoints: {},
});
