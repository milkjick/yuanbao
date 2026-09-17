3.4.4 change: NativeToolCard no longer attaches to Activity decor/root and does not create a Dialog/Window.
It discovers YuanBao chat_recycler_view and renders the MCP card through that RecyclerView's ViewGroupOverlay,
so the card is visually inside the chat conversation viewport and follows chat layout/scroll changes.
This is an inline chat-area presentation, not a separate floating window. A true adapter-backed message item
would require identifying YuanBao's private RecyclerView adapter/message model; this build intentionally avoids
inventing private message APIs.
