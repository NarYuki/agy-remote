use super::{DefaultRouteDetails, HomeRouter, State};

pub(super) async fn get_state() -> State {
    super::netdev_impl::get_state().await
}

pub(super) async fn default_route() -> Option<DefaultRouteDetails> {
    None
}

pub(super) fn home_router() -> Option<HomeRouter> {
    None
}
