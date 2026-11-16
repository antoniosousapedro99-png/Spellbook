import pytest

from cybernomicon.authorization import Authorization, AuthorizationError


def test_empty_authorization_denies_everything():
    auth = Authorization([])
    assert auth.is_empty
    assert not auth.is_authorized("10.0.0.1")
    with pytest.raises(AuthorizationError):
        auth.assert_authorized("10.0.0.1")


def test_cidr_membership():
    auth = Authorization(["192.168.1.0/24"])
    assert auth.is_authorized("192.168.1.50")
    assert not auth.is_authorized("192.168.2.50")
    auth.assert_authorized("192.168.1.1")  # no raise


def test_out_of_scope_raises():
    auth = Authorization(["10.0.0.0/8"])
    with pytest.raises(AuthorizationError):
        auth.assert_authorized("172.16.0.1")


def test_expand_single_host():
    auth = Authorization(["127.0.0.1/32"])
    assert auth.expand("127.0.0.1/32") == ["127.0.0.1"]


def test_expand_requires_authorized_scope():
    auth = Authorization(["192.168.0.0/24"])
    with pytest.raises(AuthorizationError):
        auth.expand("10.0.0.0/24")


def test_expand_caps_huge_ranges():
    auth = Authorization(["10.0.0.0/8"])
    with pytest.raises(AuthorizationError):
        auth.expand("10.0.0.0/8", max_hosts=256)


def test_malformed_scope_authorizes_nothing():
    auth = Authorization(["not-a-cidr"])
    assert auth.is_empty
